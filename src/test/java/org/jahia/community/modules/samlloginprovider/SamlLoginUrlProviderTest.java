package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginUrlProviderTest {

    private static final String SITE = "mysite";
    private static final String LISTED_HOST = "configured.example.com";
    /** Another server name of the same site, not in its host list. */
    private static final String UNLISTED_HOST = "other.example.com";

    @Mock
    private HttpServletRequest request;

    /** host -> siteKey, as the site lookup resolves it. */
    private final Map<String, String> siteByHost = new HashMap<>();
    /** siteKey -> configured hosts. */
    private final Map<String, List<String>> hostsBySite = new HashMap<>();
    /** Sites with saml-login-provider enabled. */
    private final Set<String> moduleEnabledOn = new HashSet<>();
    /** Sites with a SAML connector set up. */
    private final Set<String> samlConfiguredOn = new HashSet<>();
    /** Hosts declared by several sites. */
    private final Set<String> sharedHosts = new HashSet<>();

    private SamlLoginUrlProvider provider;

    @BeforeEach
    void setUp() {
        SiteResolver resolver = new SiteResolver() {
            @Override
            public String siteKeyFor(String serverName) {
                return siteByHost.get(serverName);
            }

            @Override
            public boolean isUnclaimed(String serverName) {
                return !siteByHost.containsKey(serverName) && !sharedHosts.contains(serverName);
            }

            @Override
            public boolean isModuleEnabled(String siteKey) {
                return moduleEnabledOn.contains(siteKey);
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
        SiteHostsStore store = new SiteHostsStore() {
            @Override
            public List<String> hosts(String siteKey) {
                return hostsBySite.getOrDefault(siteKey, List.of());
            }

            @Override
            public void save(String siteKey, List<String> hosts) {
                throw new UnsupportedOperationException();
            }
        };
        provider = new SamlLoginUrlProvider(store, resolver, samlConfiguredOn::contains);
        siteByHost.put(LISTED_HOST, SITE);
        siteByHost.put(UNLISTED_HOST, SITE);
        // By default the site qualifies: module enabled and SAML set up
        moduleEnabledOn.add(SITE);
        samlConfiguredOn.add(SITE);
    }

    @Test
    void getLoginUrl_listedHostOfItsSite_returnsSamlConnectPath() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("/ctx");
        stubPlainRequest("/protected/page", null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).startsWith("/ctx/home.connect.saml");
    }

    @Test
    void getLoginUrl_hostIsMatchedCaseInsensitively() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn("Configured.EXAMPLE.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/some/page", null);

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).startsWith("/home.connect.saml");
    }

    @Test
    void getLoginUrl_unlistedHostOfAQualifyingSite_returnsChoiceLoginPath() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(UNLISTED_HOST);
        when(request.getContextPath()).thenReturn("/ctx");
        stubPlainRequest("/protected/page", null);

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).startsWith("/ctx/saml-login");
    }

    @Test
    void getLoginUrl_qualifyingSiteWithoutHostList_returnsChoiceLoginPath() {
        // Arrange: module enabled + SAML set up, but nobody is sent to SAML automatically
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/protected/page", null);

        // Act / Assert: the modified login page, offering both ways in
        assertThat(provider.getLoginUrl(request)).startsWith("/saml-login");
    }

    @Test
    void getLoginUrl_moduleNotEnabledOnTheSite_returnsNull() {
        // Arrange: even a listed host -- the module only acts where it is enabled
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        moduleEnabledOn.remove(SITE);
        when(request.getServerName()).thenReturn(LISTED_HOST);

        // Act / Assert: stock /cms/login
        assertThat(provider.getLoginUrl(request)).isNull();
        assertThat(provider.getLogoutUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_noSamlConnectorOnTheSite_returnsNull() {
        // Arrange: a listed host whose site has no SAML set up would be a dead end (400) -- never send
        // anyone there, and do not offer a "Sign in with Google" button that cannot work either.
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        samlConfiguredOn.remove(SITE);
        when(request.getServerName()).thenReturn(LISTED_HOST);

        // Act / Assert: stock /cms/login
        assertThat(provider.getLoginUrl(request)).isNull();
        assertThat(provider.getLogoutUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_hostOfNoSite_whenSystemsiteDoesNotQualify_returnsNull() {
        // Arrange: the host falls back to systemsite, which has neither the module nor SAML here
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn("unknown.example.com");

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_hostOfNoSite_fallsBackToSystemsite_choicePage() {
        // Arrange: e.g. an editing host declared by no site, systemsite set up for SAML
        moduleEnabledOn.add(LoginSite.SYSTEMSITE);
        samlConfiguredOn.add(LoginSite.SYSTEMSITE);
        when(request.getServerName()).thenReturn("edit.example.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/start", null);

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).isEqualTo("/saml-login?redirect=%2Fstart");
    }

    @Test
    void getLoginUrl_hostOfNoSite_listedBySystemsite_goesToSamlWithTheSystemsiteKey() {
        // Arrange: the valve cannot find a site from such a host, so the connect URL must name it
        moduleEnabledOn.add(LoginSite.SYSTEMSITE);
        samlConfiguredOn.add(LoginSite.SYSTEMSITE);
        hostsBySite.put(LoginSite.SYSTEMSITE, List.of("edit.example.com"));
        when(request.getServerName()).thenReturn("edit.example.com");
        when(request.getContextPath()).thenReturn("/ctx");
        stubPlainRequest("/start", null);

        // Act / Assert
        assertThat(provider.getLoginUrl(request))
                .isEqualTo("/ctx/home.connect.saml?siteKey=systemsite&redirect=%2Fstart");
        assertThat(provider.getLogoutUrl(request)).isEqualTo("/ctx/cms/logout?redirect=%2Fctx%2F");
    }

    @Test
    void getLoginUrl_hostOfNoSite_listedBySystemsite_withoutRedirect_keepsTheSiteKey() {
        moduleEnabledOn.add(LoginSite.SYSTEMSITE);
        samlConfiguredOn.add(LoginSite.SYSTEMSITE);
        hostsBySite.put(LoginSite.SYSTEMSITE, List.of("edit.example.com"));
        when(request.getServerName()).thenReturn("edit.example.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/home.connect.saml", null);

        assertThat(provider.getLoginUrl(request)).isEqualTo("/home.connect.saml?siteKey=systemsite");
    }

    @Test
    void getLoginUrl_hostSharedBySeveralSites_neverFallsBack() {
        // Arrange: systemsite qualifies, but a shared host is never governed by any single site
        moduleEnabledOn.add(LoginSite.SYSTEMSITE);
        samlConfiguredOn.add(LoginSite.SYSTEMSITE);
        sharedHosts.add("shared.example.com");
        when(request.getServerName()).thenReturn("shared.example.com");

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_hostListedByAnotherSite_followsItsOwnSite() {
        // Arrange: site "other" lists a host that resolves to SITE. Only SITE's (empty) list counts,
        // so this is SITE's choice page, never a SAML redirect decided by "other".
        hostsBySite.put("other", List.of(LISTED_HOST));
        moduleEnabledOn.add("other");
        samlConfiguredOn.add("other");
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/protected/page", null);

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).startsWith("/saml-login");
    }

    @Test
    void getLoginUrl_noServerName_returnsNull() {
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(null);

        assertThat(provider.getLoginUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_anyLookupFailure_returnsNull() {
        // Arrange: a failure anywhere in the decision must degrade to the stock login
        SamlLoginUrlProvider failing = new SamlLoginUrlProvider(new SiteHostsStore() {
            @Override
            public List<String> hosts(String siteKey) {
                return List.of(LISTED_HOST);
            }

            @Override
            public void save(String siteKey, List<String> hosts) {
                throw new UnsupportedOperationException();
            }
        }, new SiteResolver() {
            @Override
            public String siteKeyFor(String serverName) {
                return SITE;
            }

            @Override
            public boolean isUnclaimed(String serverName) {
                return false;
            }

            @Override
            public boolean isModuleEnabled(String siteKey) {
                return true;
            }

            @Override
            public Set<String> sitesClaiming(String serverName) {
                return Set.of();
            }

            @Override
            public Set<String> serverNamesOf(String siteKey) {
                return Set.of();
            }
        }, siteKey -> {
            throw new IllegalStateException("jahia-authentication unavailable");
        });
        when(request.getServerName()).thenReturn(LISTED_HOST);

        // Act / Assert
        assertThat(failing.getLoginUrl(request)).isNull();
        assertThat(failing.getLogoutUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_errorDispatchAttributes_appendEncodedRedirect() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("");
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/protected/page");
        when(request.getQueryString()).thenReturn("a=1&b=2");

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo("/home.connect.saml?redirect=%2Fprotected%2Fpage%3Fa%3D1%26b%3D2");
    }

    @Test
    void getLoginUrl_forwardAttributes_appendEncodedRedirect() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("");
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn(null);
        when(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)).thenReturn("/pretty/url");
        when(request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING)).thenReturn("q=1");

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo("/home.connect.saml?redirect=%2Fpretty%2Furl%3Fq%3D1");
    }

    static Stream<Arguments> plainRequestCases() {
        return Stream.of(
                // plain request: redirect is the encoded original URI + query
                Arguments.of("/plain/url", "z=9", "/home.connect.saml?redirect=%2Fplain%2Furl%3Fz%3D9"),
                // loop guard: original URL ends in ".saml" -> no redirect appended
                Arguments.of("/home.connect.saml", null, "/home.connect.saml"),
                // loop guard: original URL ends in the choice-login path -> no redirect appended
                Arguments.of("/saml-login", null, "/home.connect.saml"),
                // original URL over the max length -> no redirect appended
                Arguments.of("/" + "a".repeat(2049), null, "/home.connect.saml"),
                // every resolution branch (error/forward/plain) yields null -> no redirect appended
                Arguments.of(null, null, "/home.connect.saml"));
    }

    @ParameterizedTest
    @MethodSource("plainRequestCases")
    void getLoginUrl_plainRequest_appendsRedirectOnlyWhenSafe(String uri, String query, String expected) {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest(uri, query);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo(expected);
    }

    @Test
    void getLoginUrl_uriAttributeWithEmbeddedQueryString_loopGuardStillApplies() {
        // Arrange: a url-rewrite rule folded the query into the error-dispatch URI attribute itself
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("");
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn("/home.connect.saml?x=y");
        when(request.getQueryString()).thenReturn(null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert: stripped of its query, the URI still ends in ".saml" -> loop guard applies
        assertThat(loginUrl).isEqualTo("/home.connect.saml");
    }

    @Test
    void hasCustomLoginUrl_alwaysTrue() {
        assertThat(provider.hasCustomLoginUrl()).isTrue();
    }

    @Test
    void hasCustomLogoutUrl_alwaysTrue() {
        assertThat(provider.hasCustomLogoutUrl()).isTrue();
    }

    @Test
    void getLogoutUrl_listedHost_redirectsToPublicLandingPage() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(LISTED_HOST);
        when(request.getContextPath()).thenReturn("/ctx");

        // Act
        String logoutUrl = provider.getLogoutUrl(request);

        // Assert
        assertThat(logoutUrl).isEqualTo("/ctx/cms/logout?redirect=%2Fctx%2F");
    }

    @Test
    void getLogoutUrl_unlistedHostOfAQualifyingSite_returnsNull() {
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(UNLISTED_HOST);

        assertThat(provider.getLogoutUrl(request)).isNull();
    }

    private void stubPlainRequest(String uri, String query) {
        // request.getAttribute(...) defaults to null on an unstubbed mock, matching
        // "no error dispatch / no forward" so resolution falls through to the plain branch.
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getQueryString()).thenReturn(query);
    }
}
