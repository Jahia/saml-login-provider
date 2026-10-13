package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LoginSiteTest {

    /** digitall.example.com -> digitall; shared.example.com -> two sites; anything else -> no site. */
    private final SiteResolver resolver = new SiteResolver() {
        private final Map<String, String> unique = Map.of("digitall.example.com", "digitall");
        private final Set<String> shared = Set.of("shared.example.com");

        @Override
        public String siteKeyFor(String serverName) {
            return unique.get(serverName);
        }

        @Override
        public boolean isUnclaimed(String serverName) {
            return !unique.containsKey(serverName) && !shared.contains(serverName);
        }

        @Override
        public boolean isModuleEnabled(String siteKey) {
            return false;
        }

        @Override
        public Set<String> sitesClaiming(String serverName) {
            return Set.of();
        }

        @Override
        public Set<String> serverNamesOf(String siteKey) {
            return Set.of();
        }
    };

    @Test
    void hostOfOneSite_isGovernedByThatSite() {
        LoginSite site = LoginSite.of(resolver, "digitall.example.com");

        assertThat(site.siteKey()).isEqualTo("digitall");
        assertThat(site.isFallback()).isFalse();
    }

    @Test
    void hostOfNoSite_fallsBackToSystemsite() {
        LoginSite site = LoginSite.of(resolver, "edit.example.com");

        assertThat(site.siteKey()).isEqualTo(LoginSite.SYSTEMSITE);
        assertThat(site.isFallback()).isTrue();
    }

    @Test
    void hostSharedBySeveralSites_isGovernedByNoSite() {
        // Never fall back for a shared host: Jahia serves it from an arbitrary one of its sites
        assertThat(LoginSite.of(resolver, "shared.example.com")).isNull();
    }

    @Test
    void noHost_isGovernedByNoSite() {
        assertThat(LoginSite.of(resolver, null)).isNull();
    }
}
