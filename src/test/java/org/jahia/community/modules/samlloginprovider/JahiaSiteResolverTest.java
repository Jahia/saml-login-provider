package org.jahia.community.modules.samlloginprovider;

import org.jahia.community.modules.samlloginprovider.JahiaSiteResolver.SiteInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
}
