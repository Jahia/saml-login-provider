package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginUrlProviderTest {

    @Mock
    private HttpServletRequest request;

    private SamlLoginUrlProvider provider;

    @BeforeEach
    void setUp() {
        provider = new SamlLoginUrlProvider();
    }

    @Test
    void activate_commaSeparatedFqdn_isParsedLowercasedAndTrimmed() {
        // Arrange
        activateWithFqdn(" Example.COM , Foo.EXAMPLE.com ");
        when(request.getServerName()).thenReturn("example.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/some/page", null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert: the configured host (lower-cased) matches -> straight SAML redirect
        assertThat(loginUrl).startsWith("/home.connect.saml");
    }

    @Test
    void activate_missingFqdnProperty_resultsInEmptyHostSet() {
        // Arrange
        provider.activate(Collections.emptyMap());

        // Act: hosts.isEmpty() short-circuits before any request inspection
        String loginUrl = provider.getLoginUrl(request);

        // Assert: fail-safe default -> provider inactive
        assertThat(loginUrl).isNull();
    }

    @Test
    void getLoginUrl_noConfiguration_returnsNull() {
        // Act: provider never activated -> internal host set defaults to empty
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isNull();
    }

    @Test
    void getLoginUrl_hostInList_returnsSamlConnectPath() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("/ctx");
        stubPlainRequest("/protected/page", null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).startsWith("/ctx/home.connect.saml");
    }

    @Test
    void getLoginUrl_hostNotInList_returnsChoiceLoginPath() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("other.example.com");
        when(request.getContextPath()).thenReturn("/ctx");
        stubPlainRequest("/protected/page", null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).startsWith("/ctx/saml-login");
    }

    @Test
    void getLoginUrl_errorDispatchAttributes_appendEncodedRedirect() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
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
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("");
        when(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)).thenReturn(null);
        when(request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI)).thenReturn("/pretty/url");
        when(request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING)).thenReturn("q=1");

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo("/home.connect.saml?redirect=%2Fpretty%2Furl%3Fq%3D1");
    }

    @Test
    void getLoginUrl_plainRequest_appendsEncodedRedirect() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/plain/url", "z=9");

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo("/home.connect.saml?redirect=%2Fplain%2Furl%3Fz%3D9");
    }

    @Test
    void getLoginUrl_originalUrlEndingInDotSaml_hasNoRedirectParam() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/home.connect.saml", null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert: loop guard -> no redirect appended
        assertThat(loginUrl).isEqualTo("/home.connect.saml");
    }

    @Test
    void getLoginUrl_originalUrlEndingInChoiceLoginPath_hasNoRedirectParam() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest("/saml-login", null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo("/home.connect.saml");
    }

    @Test
    void getLoginUrl_originalUrlOverMaxLength_hasNoRedirectParam() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("");
        String hugeUri = "/" + "a".repeat(2049);
        stubPlainRequest(hugeUri, null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo("/home.connect.saml");
    }

    @Test
    void getLoginUrl_requestUriNull_hasNoRedirectParam() {
        // Arrange: every resolution branch (error/forward/plain) yields null
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("");
        stubPlainRequest(null, null);

        // Act
        String loginUrl = provider.getLoginUrl(request);

        // Assert
        assertThat(loginUrl).isEqualTo("/home.connect.saml");
    }

    @Test
    void getLoginUrl_uriAttributeWithEmbeddedQueryString_loopGuardStillApplies() {
        // Arrange: a url-rewrite rule folded the query into the error-dispatch URI attribute itself
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
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
    void getLogoutUrl_hostNotInList_returnsNull() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("other.example.com");

        // Act
        String logoutUrl = provider.getLogoutUrl(request);

        // Assert
        assertThat(logoutUrl).isNull();
    }

    @Test
    void getLogoutUrl_noConfiguration_returnsNull() {
        // Act: hosts.isEmpty() short-circuits before any request inspection
        String logoutUrl = provider.getLogoutUrl(request);

        // Assert
        assertThat(logoutUrl).isNull();
    }

    @Test
    void getLogoutUrl_hostInList_redirectsToPublicLandingPage() {
        // Arrange
        activateWithFqdn("configured.example.com");
        when(request.getServerName()).thenReturn("configured.example.com");
        when(request.getContextPath()).thenReturn("/ctx");

        // Act
        String logoutUrl = provider.getLogoutUrl(request);

        // Assert
        assertThat(logoutUrl).isEqualTo("/ctx/cms/logout?redirect=%2Fctx%2F");
    }

    private void activateWithFqdn(String fqdn) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(SamlLoginUrlProvider.FQDN_PROPERTY, fqdn);
        provider.activate(properties);
    }

    private void stubPlainRequest(String uri, String query) {
        // request.getAttribute(...) defaults to null on an unstubbed mock, matching
        // "no error dispatch / no forward" so resolution falls through to the plain branch.
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getQueryString()).thenReturn(query);
    }
}
