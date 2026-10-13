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

    /** host -> siteKey, as JahiaSitesService would resolve it. */
    private final Map<String, String> siteByHost = new HashMap<>();
    /** siteKey -> configured hosts. */
    private final Map<String, List<String>> hostsBySite = new HashMap<>();

    private SamlLoginUrlProvider provider;

    @BeforeEach
    void setUp() {
        SiteResolver resolver = new SiteResolver() {
            @Override
            public String siteKeyFor(String serverName) {
                return siteByHost.get(serverName);
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
        provider = new SamlLoginUrlProvider(store, resolver);
        siteByHost.put(LISTED_HOST, SITE);
        siteByHost.put(UNLISTED_HOST, SITE);
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
    void getLoginUrl_unlistedHostOfAConfiguredSite_returnsChoiceLoginPath() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(UNLISTED_HOST);
        when(request.getContextPath()).thenReturn("/ctx");
        stubPlainRequest("/protected/page", null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).startsWith("/ctx/saml-login");
    }

    @Test
    void getLoginUrl_siteWithoutConfiguration_returnsNull() {
        // Arrange: the host resolves to a site, but that site has no host list
        when(request.getServerName()).thenReturn(LISTED_HOST);

        // Act / Assert: fail-safe -- stock /cms/login on unconfigured sites
        assertThat(provider.getLoginUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_hostResolvingToNoSite_returnsNull() {
        // Arrange
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn("unknown.example.com");

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_hostListedByAnotherSite_doesNotApply() {
        // Arrange: site "other" lists a host that resolves to SITE, which is unconfigured. This is
        // the isolation that keeps a site admin's list from affecting any site but their own.
        hostsBySite.put("other", List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(LISTED_HOST);

        // Act / Assert
        assertThat(provider.getLoginUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_noServerName_returnsNull() {
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(null);

        assertThat(provider.getLoginUrl(request)).isNull();
    }

    @Test
    void getLoginUrl_siteResolutionFails_returnsNull() {
        // Arrange: a resolver failure must degrade to the stock login, never break it
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
                throw new IllegalStateException("site service unavailable");
            }

            @Override
            public Set<String> sitesClaiming(String serverName) {
                return Set.of();
            }

            @Override
            public Set<String> serverNamesOf(String siteKey) {
                return Set.of();
            }
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
    void getLogoutUrl_unlistedHostOfAConfiguredSite_returnsNull() {
        hostsBySite.put(SITE, List.of(LISTED_HOST));
        when(request.getServerName()).thenReturn(UNLISTED_HOST);

        assertThat(provider.getLogoutUrl(request)).isNull();
    }

    @Test
    void getLogoutUrl_siteWithoutConfiguration_returnsNull() {
        when(request.getServerName()).thenReturn(LISTED_HOST);

        assertThat(provider.getLogoutUrl(request)).isNull();
    }

    private void stubPlainRequest(String uri, String query) {
        // request.getAttribute(...) defaults to null on an unstubbed mock, matching
        // "no error dispatch / no forward" so resolution falls through to the plain branch.
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getQueryString()).thenReturn(query);
    }
}
