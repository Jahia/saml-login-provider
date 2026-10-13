package org.jahia.community.modules.samlloginprovider;

import org.jahia.api.Constants;
import org.jahia.community.modules.samlloginprovider.JahiaSiteResolver.SiteInfo;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.sites.JahiaSitesService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JahiaSiteResolverTest {

    private static final Set<String> WITH_MODULE = Set.of("default", JahiaSiteResolver.MODULE_ID);
    private static final Set<String> WITHOUT_MODULE = Set.of("default");

    private final AtomicReference<Map<String, SiteInfo>> sites = new AtomicReference<>(Map.of(
            "digitall", new SiteInfo(Set.of("digitall.example.com", "www.digitall.example.com"), WITH_MODULE),
            "other", new SiteInfo(Set.of("other.example.com"), WITHOUT_MODULE),
            "systemsite", new SiteInfo(Set.of("localhost"), WITH_MODULE),
            "uisite", new SiteInfo(Set.of("localhost"), WITHOUT_MODULE)));
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000);

    private final JahiaSiteResolver resolver = new JahiaSiteResolver(() -> {
        reads.incrementAndGet();
        return sites.get();
    }, now::get);

    @Test
    void siteKeyFor_hostOfExactlyOneSite_resolvesByServerNameOrAlias() {
        assertThat(resolver.siteKeyFor("digitall.example.com")).isEqualTo("digitall");
        assertThat(resolver.siteKeyFor("www.digitall.example.com")).isEqualTo("digitall");
    }

    @Test
    void siteKeyFor_isCaseInsensitive() {
        assertThat(resolver.siteKeyFor("Digitall.EXAMPLE.com")).isEqualTo("digitall");
    }

    @Test
    void siteKeyFor_unknownOrNullHost_isNull() {
        assertThat(resolver.siteKeyFor("unknown.example.com")).isNull();
        assertThat(resolver.siteKeyFor(null)).isNull();
    }

    @Test
    void siteKeyFor_hostClaimedBySeveralSites_isNull() {
        // Jahia's own lookup returns an arbitrary one of them: no site's list may govern such a host
        assertThat(resolver.siteKeyFor("localhost")).isNull();
    }

    @Test
    void isModuleEnabled_followsTheSiteInstalledModules() {
        assertThat(resolver.isModuleEnabled("digitall")).isTrue();
        assertThat(resolver.isModuleEnabled("other")).isFalse();
        assertThat(resolver.isModuleEnabled("missing")).isFalse();
        assertThat(resolver.isModuleEnabled(null)).isFalse();
    }

    @Test
    void sitesClaiming_returnsEveryClaimant() {
        assertThat(resolver.sitesClaiming("localhost")).containsExactlyInAnyOrder("systemsite", "uisite");
        assertThat(resolver.sitesClaiming("other.example.com")).containsExactly("other");
        assertThat(resolver.sitesClaiming("unknown.example.com")).isEmpty();
    }

    @Test
    void serverNamesOf_returnsTheSiteNames() {
        assertThat(resolver.serverNamesOf("digitall")).containsExactlyInAnyOrder("digitall.example.com", "www.digitall.example.com");
        assertThat(resolver.serverNamesOf("missing")).isEmpty();
    }

    @Test
    void routingLookups_cacheTheIndexBriefly_thenRereadIt() {
        // Arrange
        resolver.siteKeyFor("digitall.example.com");
        resolver.isModuleEnabled("digitall");
        assertThat(reads).hasValue(1);
        sites.set(Map.of("digitall", new SiteInfo(Set.of("renamed.example.com"), WITHOUT_MODULE)));

        // Act: past the TTL the routing index is rebuilt
        now.addAndGet(JahiaSiteResolver.ROUTING_CACHE_TTL_MS + 1);

        // Assert
        assertThat(resolver.siteKeyFor("renamed.example.com")).isEqualTo("digitall");
        assertThat(resolver.siteKeyFor("digitall.example.com")).isNull();
        assertThat(resolver.isModuleEnabled("digitall")).isFalse();
        assertThat(reads).hasValue(2);
    }

    @Test
    void sitesClaimingAndServerNamesOf_alwaysReadFreshData() {
        // Arrange: the routing index is warm
        resolver.siteKeyFor("digitall.example.com");
        sites.set(Map.of("digitall", new SiteInfo(Set.of("renamed.example.com"), WITH_MODULE)));

        // Act / Assert: validation must never act on a stale view
        assertThat(resolver.serverNamesOf("digitall")).containsExactly("renamed.example.com");
        assertThat(resolver.sitesClaiming("renamed.example.com")).containsExactly("digitall");
    }

    @Test
    void readFailure_degradesToNoSite() {
        JahiaSiteResolver failing = new JahiaSiteResolver(() -> {
            throw new IllegalStateException("repository unavailable");
        }, now::get);

        assertThat(failing.siteKeyFor("digitall.example.com")).isNull();
        assertThat(failing.isModuleEnabled("digitall")).isFalse();
        assertThat(failing.sitesClaiming("digitall.example.com")).isEmpty();
        assertThat(failing.serverNamesOf("digitall")).isEmpty();
    }

    @Test
    void routingRefresh_isSingleFlight_concurrentCallersKeepTheStaleSnapshot() {
        // Arrange: a warm index, then expired; the refresh read re-enters the resolver,
        // standing in for a request that arrives while the rebuild is in progress
        AtomicReference<JahiaSiteResolver> self = new AtomicReference<>();
        List<String> seenDuringRefresh = new ArrayList<>();
        AtomicInteger readsSeenDuringRefresh = new AtomicInteger();
        Map<String, SiteInfo> renamed = Map.of("digitall", new SiteInfo(Set.of("renamed.example.com"), WITH_MODULE));
        AtomicInteger calls = new AtomicInteger();
        JahiaSiteResolver reentrant = new JahiaSiteResolver(() -> {
            if (calls.incrementAndGet() == 1) {
                return sites.get();
            }
            seenDuringRefresh.add(self.get().siteKeyFor("digitall.example.com"));
            readsSeenDuringRefresh.set(calls.get());
            return renamed;
        }, now::get);
        self.set(reentrant);
        assertThat(reentrant.siteKeyFor("digitall.example.com")).isEqualTo("digitall");
        now.addAndGet(JahiaSiteResolver.ROUTING_CACHE_TTL_MS + 1);

        // Act
        String afterRefresh = reentrant.siteKeyFor("renamed.example.com");

        // Assert: the nested caller got the stale answer without a second read
        assertThat(seenDuringRefresh).containsExactly("digitall");
        assertThat(readsSeenDuringRefresh).hasValue(2);
        assertThat(calls).hasValue(2);
        assertThat(afterRefresh).isEqualTo("digitall");
        assertThat(reentrant.siteKeyFor("digitall.example.com")).isNull();
    }

    @Test
    void routingRefresh_failingRebuild_releasesTheRefreshForTheNextCaller() {
        // Arrange: a warm index, then expired, then one rebuild that blows up
        AtomicInteger calls = new AtomicInteger();
        JahiaSiteResolver flaky = new JahiaSiteResolver(() -> calls.incrementAndGet() == 2 ? null : sites.get(), now::get);
        flaky.siteKeyFor("digitall.example.com");
        now.addAndGet(JahiaSiteResolver.ROUTING_CACHE_TTL_MS + 1);
        assertThatThrownBy(() -> flaky.siteKeyFor("digitall.example.com")).isInstanceOf(NullPointerException.class);
        sites.set(Map.of("digitall", new SiteInfo(Set.of("renamed.example.com"), WITH_MODULE)));

        // Act
        String resolved = flaky.siteKeyFor("renamed.example.com");

        // Assert: the next caller rebuilt instead of being stuck on the stale snapshot
        assertThat(resolved).isEqualTo("digitall");
        assertThat(calls).hasValue(3);
    }

    @Test
    void readSites_usesASystemSessionInTheDefaultWorkspace_neverTheCallerSession() throws RepositoryException {
        // Arrange: like the real template, the session is logged out once the callback returns,
        // and JCRSiteNode then logs an error and returns null for every property
        JCRTemplate template = mock(JCRTemplate.class);
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JCRSessionWrapper systemSession = mock(JCRSessionWrapper.class);
        AtomicBoolean sessionClosed = new AtomicBoolean();
        JCRSiteNode site = site(sessionClosed, "digitall", "Digitall.example.com",
                List.of("www.digitall.example.com"), Set.of(JahiaSiteResolver.MODULE_ID));
        when(sitesService.getSitesNodeList(systemSession)).thenReturn(List.of(site));
        stubSystemSession(template, systemSession, sessionClosed);

        try (MockedStatic<JCRTemplate> templates = mockStatic(JCRTemplate.class);
             MockedStatic<JahiaSitesService> services = mockStatic(JahiaSitesService.class)) {
            templates.when(JCRTemplate::getInstance).thenReturn(template);
            services.when(JahiaSitesService::getInstance).thenReturn(sitesService);

            // Act
            JahiaSiteResolver production = new JahiaSiteResolver();

            // Assert: everything was read while the session was still open
            assertThat(production.serverNamesOf("digitall"))
                    .containsExactlyInAnyOrder("digitall.example.com", "www.digitall.example.com");
            assertThat(production.isModuleEnabled("digitall")).isTrue();
            assertThat(production.siteKeyFor("www.digitall.example.com")).isEqualTo("digitall");
            // one fresh validation read, one routing build: both as system, in the default workspace
            verify(template, times(2)).doExecuteWithSystemSessionAsUser(isNull(), eq(Constants.EDIT_WORKSPACE), isNull(), any());
            verify(sitesService, never()).getSitesNodeList();
        }
    }

    @Test
    void readSites_siteWithNullKey_isSkippedWithoutLosingTheOthers() throws RepositoryException {
        // Arrange
        JCRTemplate template = mock(JCRTemplate.class);
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JCRSessionWrapper systemSession = mock(JCRSessionWrapper.class);
        AtomicBoolean sessionClosed = new AtomicBoolean();
        JCRSiteNode broken = site(sessionClosed, null, "broken.example.com", List.of(), Set.of(JahiaSiteResolver.MODULE_ID));
        JCRSiteNode digitall = site(sessionClosed, "digitall", "digitall.example.com", null, Set.of(JahiaSiteResolver.MODULE_ID));
        when(sitesService.getSitesNodeList(systemSession)).thenReturn(List.of(broken, digitall));
        stubSystemSession(template, systemSession, sessionClosed);

        try (MockedStatic<JCRTemplate> templates = mockStatic(JCRTemplate.class);
             MockedStatic<JahiaSitesService> services = mockStatic(JahiaSitesService.class)) {
            templates.when(JCRTemplate::getInstance).thenReturn(template);
            services.when(JahiaSitesService::getInstance).thenReturn(sitesService);

            // Act
            JahiaSiteResolver production = new JahiaSiteResolver();

            // Assert
            assertThat(production.siteKeyFor("digitall.example.com")).isEqualTo("digitall");
            assertThat(production.serverNamesOf("digitall")).containsExactly("digitall.example.com");
            assertThat(production.siteKeyFor("broken.example.com")).isNull();
        }
    }

    /** A site node whose getters return null once the session is closed, as JCRSiteNode does. */
    private static JCRSiteNode site(AtomicBoolean sessionClosed, String key, String serverName,
                                    List<String> aliases, Set<String> modules) {
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getSiteKey()).thenAnswer(invocation -> sessionClosed.get() ? null : key);
        when(site.getServerName()).thenAnswer(invocation -> sessionClosed.get() ? null : serverName);
        when(site.getServerNameAliases()).thenAnswer(invocation -> sessionClosed.get() ? null : aliases);
        when(site.getInstalledModulesWithAllDependencies()).thenAnswer(invocation -> sessionClosed.get() ? null : modules);
        return site;
    }

    /** Runs the callback with the session, then logs it out, as the real template does in a finally. */
    private static void stubSystemSession(JCRTemplate template, JCRSessionWrapper session, AtomicBoolean sessionClosed)
            throws RepositoryException {
        when(template.doExecuteWithSystemSessionAsUser(isNull(), eq(Constants.EDIT_WORKSPACE), isNull(), any()))
                .thenAnswer(invocation -> {
                    sessionClosed.set(false);
                    try {
                        return invocation.<JCRCallback<?>>getArgument(3).doInJCR(session);
                    } finally {
                        sessionClosed.set(true);
                    }
                });
    }

    @Test
    void readSites_repositoryFailure_degradesToNoSite() throws RepositoryException {
        // Arrange
        JCRTemplate template = mock(JCRTemplate.class);
        when(template.doExecuteWithSystemSessionAsUser(isNull(), eq(Constants.EDIT_WORKSPACE), isNull(), any()))
                .thenThrow(new RepositoryException("repository unavailable"));

        try (MockedStatic<JCRTemplate> templates = mockStatic(JCRTemplate.class)) {
            templates.when(JCRTemplate::getInstance).thenReturn(template);

            // Act
            JahiaSiteResolver production = new JahiaSiteResolver();

            // Assert
            assertThat(production.siteKeyFor("digitall.example.com")).isNull();
            assertThat(production.serverNamesOf("digitall")).isEmpty();
        }
    }
}
