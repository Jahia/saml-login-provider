package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.FilterChain;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlRedirectCookieFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private SamlRedirectCookieFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SamlRedirectCookieFilter();
        filter.activate();
        lenient().when(request.getRequestURI()).thenReturn("/home.connect.saml");
    }

    @Test
    void activate_runsOnTheValvesEndpointsJustBeforeTheValve() {
        // Jahia, like the servlet spec, matches *.<ext> on the text after the LAST dot only: a
        // "*.connect.saml" pattern would never match. The connect endpoint is picked in doFilter.
        assertThat(filter.getUrlPatterns()).containsExactly("*.saml");
        // the valve's SAML2Filter has order -5.4: lower runs first
        assertThat(filter.getOrder()).isLessThan(-5.4f);
    }

    @Test
    void doFilter_overHttps_sendsTheRedirectCookieOnTheIdpsCrossSitePost() throws Exception {
        // Arrange: what the valve does at connect time (SAML2Util.storeAuthenticationContext)
        lenient().when(request.isSecure()).thenReturn(true);
        givenTheValveAdds(r -> {
            Cookie cookie = new Cookie("redirect", "/start");
            cookie.setPath("/");
            cookie.setSecure(true);
            cookie.setHttpOnly(true);
            r.addCookie(cookie);
        });

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        verify(response).addHeader("Set-Cookie", "redirect=/start; Path=/; Secure; HttpOnly; SameSite=None");
        verify(response, never()).addCookie(any());
    }

    @Test
    void doFilter_behindATlsProxy_marksTheCookieSecureItself() throws Exception {
        // Arrange: Tomcat sees http (TLS ends at the proxy), so the valve leaves Secure off
        lenient().when(request.isSecure()).thenReturn(false);
        lenient().when(request.getHeader("X-Forwarded-Proto")).thenReturn("HTTPS");
        givenTheValveAdds(r -> {
            Cookie cookie = new Cookie("redirect", "/jahia/jcontent/digitall/en/pages?x=1&y=2");
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            r.addCookie(cookie);
        });

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        verify(response).addHeader("Set-Cookie",
                "redirect=/jahia/jcontent/digitall/en/pages?x=1&y=2; Path=/; Secure; HttpOnly; SameSite=None");
    }

    @Test
    void doFilter_keepsTheCookiesLifetimeAndDomain() throws Exception {
        lenient().when(request.isSecure()).thenReturn(true);
        givenTheValveAdds(r -> {
            Cookie cookie = new Cookie("redirect", "/start");
            cookie.setPath("/ctx");
            cookie.setDomain("edit.example.com");
            cookie.setMaxAge(300);
            r.addCookie(cookie);
        });

        filter.doFilter(request, response, chain);

        verify(response).addHeader("Set-Cookie",
                "redirect=/start; Path=/ctx; Domain=edit.example.com; Max-Age=300; Secure; SameSite=None");
    }

    @Test
    void doFilter_overPlainHttp_leavesTheCookieAlone() throws Exception {
        // Browsers reject SameSite=None without Secure: keep today's behavior there
        lenient().when(request.isSecure()).thenReturn(false);
        Cookie cookie = new Cookie("redirect", "/start");
        givenTheValveAdds(r -> r.addCookie(cookie));

        filter.doFilter(request, response, chain);

        verify(response).addCookie(cookie);
        verify(response, never()).addHeader(anyString(), anyString());
    }

    @Test
    void doFilter_otherCookies_areLeftAlone() throws Exception {
        lenient().when(request.isSecure()).thenReturn(true);
        Cookie cookie = new Cookie("JSESSIONID", "abc");
        givenTheValveAdds(r -> r.addCookie(cookie));

        filter.doFilter(request, response, chain);

        verify(response).addCookie(cookie);
        verify(response, never()).addHeader(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/a b", "/a;b", "/a,b", "/a\"b", "/a\\b", "/aé",
            "/a\r\nSet-Cookie: x=y", "/a\u0000"})
    void doFilter_valueOutsideRfc6265_isLeftToTheContainer(String value) throws Exception {
        // A header built by hand must never carry what Tomcat's cookie processor would refuse
        lenient().when(request.isSecure()).thenReturn(true);
        Cookie cookie = new Cookie("redirect", value);
        givenTheValveAdds(r -> r.addCookie(cookie));

        filter.doFilter(request, response, chain);

        verify(response).addCookie(cookie);
        verify(response, never()).addHeader(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/;SameSite=Lax", "/\r\nX-Injected: 1"})
    void doFilter_unsafePath_isLeftToTheContainer(String path) throws Exception {
        lenient().when(request.isSecure()).thenReturn(true);
        Cookie cookie = new Cookie("redirect", "/start");
        cookie.setPath(path);
        givenTheValveAdds(r -> r.addCookie(cookie));

        filter.doFilter(request, response, chain);

        verify(response).addCookie(cookie);
        verify(response, never()).addHeader(anyString(), anyString());
    }

    @Test
    void doFilter_unsafeDomain_isLeftToTheContainer() throws Exception {
        lenient().when(request.isSecure()).thenReturn(true);
        Cookie cookie = new Cookie("redirect", "/start");
        cookie.setDomain("example.com\r\nX-Injected: 1");
        givenTheValveAdds(r -> r.addCookie(cookie));

        filter.doFilter(request, response, chain);

        verify(response).addCookie(cookie);
        verify(response, never()).addHeader(anyString(), anyString());
    }

    @Test
    void doFilter_chainedProxies_useTheClientFacingScheme() throws Exception {
        // X-Forwarded-Proto lists the schemes left to right from the client: the first one counts
        lenient().when(request.isSecure()).thenReturn(false);
        lenient().when(request.getHeader("X-Forwarded-Proto")).thenReturn("https, http");
        givenTheValveAdds(r -> r.addCookie(new Cookie("redirect", "/start")));

        filter.doFilter(request, response, chain);

        verify(response).addHeader("Set-Cookie", "redirect=/start; Secure; SameSite=None");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/home.callback.saml", "/home.metadata.saml"})
    void doFilter_otherValveEndpoints_getTheOriginalResponse(String uri) throws Exception {
        // Only the connect endpoint stores the redirect cookie
        when(request.getRequestURI()).thenReturn(uri);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void doFilter_passesTheRequestOnToTheValve() throws Exception {
        ArgumentCaptor<ServletResponse> passed = ArgumentCaptor.forClass(ServletResponse.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(org.mockito.ArgumentMatchers.eq(request), passed.capture());
        assertThat(passed.getValue()).isInstanceOf(HttpServletResponse.class);
    }

    private void givenTheValveAdds(Consumer<HttpServletResponse> valve) throws Exception {
        doAnswer(invocation -> {
            valve.accept((HttpServletResponse) invocation.getArgument(1));
            return null;
        }).when(chain).doFilter(any(), any());
    }
}
