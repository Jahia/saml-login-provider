package org.jahia.community.modules.samlloginprovider;

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
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * {@link SiteResolver} built from every site's server name, aliases and installed modules, read
 * through {@link JahiaSitesService}.
 *
 * <p>For a host declared by exactly one site this agrees with the lookup the SAML connect endpoint
 * uses to pick its connector ({@code getSitenameByServerName}, case-insensitive, no default-site
 * fallback). A host declared by several sites resolves to no site, so it keeps the stock login.
 * "Module enabled" uses the installed modules with all dependencies, as the administration does
 * when it decides to show the module's settings screen.
 *
 * <p>The routing index is cached for {@value #ROUTING_CACHE_TTL_MS} ms: it sits on the login path
 * of every anonymous request. A server name or module change therefore reaches routing within that
 * delay. Validation ({@link #sitesClaiming}, {@link #serverNamesOf}) always reads fresh data.
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

        private Snapshot(long readAt, Map<String, SiteInfo> sites) {
            this.readAt = readAt;
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
    private volatile Snapshot routingIndex;

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
    public boolean isModuleEnabled(String siteKey) {
        return siteKey != null && routingIndex().sitesWithModule.contains(siteKey);
    }

    @Override
    public Set<String> sitesClaiming(String serverName) {
        if (serverName == null) {
            return Set.of();
        }
        return indexByHost(safeRead()).getOrDefault(lower(serverName), Set.of());
    }

    @Override
    public Set<String> serverNamesOf(String siteKey) {
        SiteInfo info = safeRead().get(siteKey);
        return info == null ? Set.of() : info.serverNames;
    }

    private Snapshot routingIndex() {
        long now = clock.getAsLong();
        Snapshot current = routingIndex;
        if (current == null || now - current.readAt > ROUTING_CACHE_TTL_MS) {
            current = new Snapshot(now, safeRead());
            routingIndex = current;
        }
        return current;
    }

    /** A read failure means "no site": the caller then leaves the login flow alone. */
    private Map<String, SiteInfo> safeRead() {
        try {
            return siteInfos.get();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the sites; treating every host as unresolved", e);
            return Map.of();
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

    private static Map<String, SiteInfo> readSites() {
        List<JCRSiteNode> sites;
        try {
            sites = JahiaSitesService.getInstance().getSitesNodeList();
        } catch (RepositoryException e) {
            throw new IllegalStateException("Could not list the sites", e);
        }
        Map<String, SiteInfo> infos = new HashMap<>();
        for (JCRSiteNode site : sites) {
            List<String> names = new ArrayList<>();
            names.add(site.getServerName());
            if (site.getServerNameAliases() != null) {
                names.addAll(site.getServerNameAliases());
            }
            Set<String> lowerCased = new HashSet<>();
            names.stream().filter(Objects::nonNull).map(JahiaSiteResolver::lower).forEach(lowerCased::add);
            Set<String> modules = site.getInstalledModulesWithAllDependencies();
            infos.put(site.getSiteKey(), new SiteInfo(lowerCased, modules == null ? Set.of() : modules));
        }
        return Map.copyOf(infos);
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
