package org.jahia.community.modules.samlloginprovider;

import org.jahia.api.Constants;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link SiteResolver} built from every site's server name, aliases and installed modules, read
 * through {@link JahiaSitesService} with a system session in the default workspace, as core's own
 * host lookup does: the index never depends on what the current caller is allowed to read.
 *
 * <p>For a host declared by exactly one site this agrees with the lookup the SAML connect endpoint
 * uses to pick its connector ({@code getSitenameByServerName}, case-insensitive, no default-site
 * fallback). A host declared by several sites resolves to no site, so it keeps the stock login.
 * "Module enabled" uses the installed modules with all dependencies, as the administration does
 * when it decides to show the module's settings screen.
 *
 * <p>The routing index is cached for {@value #ROUTING_CACHE_TTL_MS} ms: it sits on the login path
 * of every anonymous request. A server name or module change therefore reaches routing within that
 * delay. Once expired, a single caller rebuilds it while the others keep the stale snapshot, so an
 * expiry under load costs one walk of the sites, not one per request. Only the very first build,
 * when there is no snapshot yet, makes callers wait. Validation ({@link #sitesClaiming},
 * {@link #serverNamesOf}) always reads fresh data.
 */
@Component(service = SiteResolver.class, immediate = true)
public class JahiaSiteResolver implements SiteResolver {

    static final long ROUTING_CACHE_TTL_MS = 5_000;
    /** This module's id, as it appears in a site's installed modules. */
    static final String MODULE_ID = "saml-login-provider";

    private static final Logger LOGGER = LoggerFactory.getLogger(JahiaSiteResolver.class);

    /** What the resolver needs to know about one site. Immutable. */
    static final class SiteInfo {
        private final Set<String> serverNames;
        private final Set<String> modules;

        SiteInfo(Set<String> serverNames, Set<String> modules) {
            this.serverNames = Set.copyOf(serverNames);
            this.modules = Set.copyOf(modules);
        }
    }

    /** Immutable view used for routing, swapped wholesale. */
    private static final class Snapshot {
        private final long readAt;
        private final Map<String, Set<String>> sitesByHost;
        private final Set<String> sitesWithModule;
        /** False when the sites could not be read: then no host is known to be unclaimed. */
        private final boolean complete;

        private Snapshot(long readAt, Map<String, SiteInfo> sites, boolean complete) {
            this.readAt = readAt;
            this.complete = complete;
            this.sitesByHost = indexByHost(sites);
            Set<String> withModule = new HashSet<>();
            sites.forEach((siteKey, info) -> {
                if (info.modules.contains(MODULE_ID)) {
                    withModule.add(siteKey);
                }
            });
            this.sitesWithModule = Set.copyOf(withModule);
        }
    }

    private final Supplier<Map<String, SiteInfo>> siteInfos;
    private final LongSupplier clock;
    private final AtomicReference<Snapshot> routingIndex = new AtomicReference<>();
    /** Claimed by the one caller rebuilding an expired snapshot. */
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Object firstBuildLock = new Object();

    public JahiaSiteResolver() {
        this(JahiaSiteResolver::readSites, System::currentTimeMillis);
    }

    /** @param siteInfos site key -> its names and modules, read from the platform */
    JahiaSiteResolver(Supplier<Map<String, SiteInfo>> siteInfos, LongSupplier clock) {
        this.siteInfos = siteInfos;
        this.clock = clock;
    }

    @Override
    public String siteKeyFor(String serverName) {
        if (serverName == null) {
            return null;
        }
        Set<String> claimants = routingIndex().sitesByHost.getOrDefault(lower(serverName), Set.of());
        return claimants.size() == 1 ? claimants.iterator().next() : null;
    }

    @Override
    public boolean isUnclaimed(String serverName) {
        if (serverName == null) {
            return false;
        }
        Snapshot index = routingIndex();
        return index.complete && !index.sitesByHost.containsKey(lower(serverName));
    }

    @Override
    public boolean isModuleEnabled(String siteKey) {
        return siteKey != null && routingIndex().sitesWithModule.contains(siteKey);
    }

    @Override
    public Set<String> sitesClaiming(String serverName) {
        if (serverName == null) {
            return Set.of();
        }
        // Fails closed: "no site declares it" lets systemsite list the host, so it must be a real answer
        Map<String, SiteInfo> sites = tryRead()
                .orElseThrow(() -> new IllegalStateException("Could not read the sites to validate a host"));
        return indexByHost(sites).getOrDefault(lower(serverName), Set.of());
    }

    @Override
    public Set<String> serverNamesOf(String siteKey) {
        SiteInfo info = safeRead().get(siteKey);
        return info == null ? Set.of() : info.serverNames;
    }

    private Snapshot routingIndex() {
        long now = clock.getAsLong();
        Snapshot current = routingIndex.get();
        if (current == null) {
            return firstBuild(now);
        }
        if (now - current.readAt <= ROUTING_CACHE_TTL_MS || !refreshing.compareAndSet(false, true)) {
            // Fresh, or another caller is already rebuilding it: the stale snapshot is good enough
            return current;
        }
        try {
            Snapshot fresh = snapshotAt(now);
            routingIndex.set(fresh);
            return fresh;
        } finally {
            refreshing.set(false);
        }
    }

    /** No snapshot to fall back on yet: callers wait for one build instead of each reading the sites. */
    private Snapshot firstBuild(long now) {
        synchronized (firstBuildLock) {
            Snapshot current = routingIndex.get();
            if (current == null) {
                current = snapshotAt(now);
                routingIndex.set(current);
            }
            return current;
        }
    }

    private Snapshot snapshotAt(long now) {
        Optional<Map<String, SiteInfo>> read = tryRead();
        return new Snapshot(now, read.orElse(Map.of()), read.isPresent());
    }

    /** A read failure means "no site": the caller then leaves the login flow alone. */
    private Map<String, SiteInfo> safeRead() {
        return tryRead().orElse(Map.of());
    }

    /** The sites, or empty when they could not be read. */
    private Optional<Map<String, SiteInfo>> tryRead() {
        try {
            return Optional.of(siteInfos.get());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the sites; treating every host as unresolved", e);
            return Optional.empty();
        }
    }

    private static Map<String, Set<String>> indexByHost(Map<String, SiteInfo> sites) {
        Map<String, Set<String>> sitesByHost = new HashMap<>();
        sites.forEach((siteKey, info) -> info.serverNames.forEach(name ->
                sitesByHost.computeIfAbsent(lower(name), host -> new HashSet<>()).add(siteKey)));
        Map<String, Set<String>> immutable = new HashMap<>();
        sitesByHost.forEach((host, siteKeys) -> immutable.put(host, Set.copyOf(siteKeys)));
        return Map.copyOf(immutable);
    }

    /**
     * Reads every site through a system session in the default workspace, like core's
     * {@code getSitenameByServerName}: the caller's own session would hide the sites it cannot read.
     * Every property is read inside the callback, which returns only plain values: the session is
     * logged out as soon as the callback returns, and a site node read after that has lost its data.
     */
    private static Map<String, SiteInfo> readSites() {
        try {
            // A null user means the root system session, as JCRSessionFactory.getCurrentSystemSession
            return JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, Constants.EDIT_WORKSPACE, null,
                    session -> toSiteInfos(JahiaSitesService.getInstance().getSitesNodeList(session)));
        } catch (RepositoryException e) {
            throw new IllegalStateException("Could not list the sites", e);
        }
    }

    /** Must run while the session the sites were read from is still open. */
    private static Map<String, SiteInfo> toSiteInfos(List<JCRSiteNode> sites) {
        Map<String, SiteInfo> infos = new HashMap<>();
        for (JCRSiteNode site : sites) {
            String siteKey = site.getSiteKey();
            if (siteKey == null) {
                LOGGER.debug("Skipping site node {}: it has no site key", site.getPath());
                continue;
            }
            infos.put(siteKey, siteInfoOf(site));
        }
        return Map.copyOf(infos);
    }

    private static SiteInfo siteInfoOf(JCRSiteNode site) {
        List<String> names = new ArrayList<>();
        names.add(site.getServerName());
        List<String> aliases = site.getServerNameAliases();
        if (aliases != null) {
            names.addAll(aliases);
        }
        Set<String> lowerCased = new HashSet<>();
        names.stream().filter(Objects::nonNull).map(JahiaSiteResolver::lower).forEach(lowerCased::add);
        Set<String> modules = site.getInstalledModulesWithAllDependencies();
        return new SiteInfo(lowerCased, modules == null ? Set.of() : modules);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
