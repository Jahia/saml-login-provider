package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.jahia.services.usermanager.JahiaUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CmsLoginRedirectFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;
    @Mock
    private SamlLoginUrlProvider provider;

    private CmsLoginRedirectFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CmsLoginRedirectFilter();
        filter.setLoginUrlProvider(provider);
        filter.activate();
        lenient().when(request.getContextPath()).thenReturn("");
        lenient().when(request.getMethod()).thenReturn("GET");
        lenient().when(request.getRequestURI()).thenReturn("/cms/login");
        lenient().when(request.getParameter(anyString())).thenReturn(null);
        lenient().when(response.encodeRedirectURL(anyString())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void activate_coversTheLoginServlet() {
        // Jahia matches "/x/*" as a prefix; an exact "/cms/login" would miss Shiro's ";jsessionid=" form
        assertThat(filter.getUrlPatterns()).containsExactly("/cms/*");
    }

    @Test
    void doFilter_coreLoginPage_onARoutedHost_goesToTheModulesLoginKeepingTheTarget() throws Exception {
        // Arrange: core's Shiro guard for /tools sends anonymous users to /cms/login?redirect=/tools
        when(request.getParameter("redirect")).thenReturn("/tools");
        when(provider.loginUrlFor(request, "/tools")).thenReturn("/saml-login?redirect=%2Ftools");

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        verify(response).sendRedirect("/saml-login?redirect=%2Ftools");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_withShirosSessionPathParameter_isStillTheLoginPage() throws Exception {
        when(request.getContextPath()).thenReturn("/ctx");
        when(request.getRequestURI()).thenReturn("/ctx/cms/login;jsessionid=0B18D742C95DA358");
        when(request.getParameter("redirect")).thenReturn("/ctx/tools");
        when(provider.loginUrlFor(request, "/ctx/tools")).thenReturn("/ctx/saml-login?redirect=%2Fctx%2Ftools");

        filter.doFilter(request, response, chain);

        verify(response).sendRedirect("/ctx/saml-login?redirect=%2Fctx%2Ftools");
    }

    @Test
    void doFilter_onAHostTheModuleDoesNotRoute_leavesTheStockPage() throws Exception {
        when(provider.loginUrlFor(request, null)).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).sendRedirect(anyString());
    }

    @Test
    void doFilter_headRequest_isRedirectedLikeGet() throws Exception {
        when(request.getMethod()).thenReturn("HEAD");
        when(provider.loginUrlFor(request, null)).thenReturn("/saml-login");

        filter.doFilter(request, response, chain);

        verify(response).sendRedirect("/saml-login");
    }

    @Test
    void doFilter_credentialsPost_isLeftToTheLoginServlet() throws Exception {
        // The choice page's own form posts to /cms/login
        when(request.getMethod()).thenReturn("POST");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"loginError", "username", "restMode", "redirectActive"})
    void doFilter_loginServletParameters_areLeftToTheLoginServlet(String parameter) throws Exception {
        // A failed login shows its error on the stock page; a GET login or REST call must still work
        when(request.getParameter(parameter)).thenReturn("x");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(provider);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/cms/render/live/en/sites/digitall/home.html", "/cms/logout", "/cms/loginx", "/cms/login/x"})
    void doFilter_otherCmsPaths_areNotTouched(String uri) throws Exception {
        when(request.getRequestURI()).thenReturn(uri);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(provider);
    }

    @Test
    void doFilter_loggedInUser_keepsTheStockPage() throws Exception {
        // No needless SSO round trip for someone already logged in
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);
        JahiaUser user = org.mockito.Mockito.mock(JahiaUser.class);
        lenient().when(user.getUsername()).thenReturn("root");
        lenient().when(user.getName()).thenReturn("root");
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CmsLoginRedirectFilter.SESSION_USER_ATTRIBUTE)).thenReturn(user);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(provider);
    }

    @Test
    void doFilter_guestSession_isRedirected() throws Exception {
        HttpSession session = org.mockito.Mockito.mock(HttpSession.class);
        JahiaUser guest = org.mockito.Mockito.mock(JahiaUser.class);
        lenient().when(guest.getUsername()).thenReturn("guest");
        lenient().when(guest.getName()).thenReturn("guest");
        lenient().when(guest.getRealm()).thenReturn(null);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(CmsLoginRedirectFilter.SESSION_USER_ATTRIBUTE)).thenReturn(guest);
        when(provider.loginUrlFor(request, null)).thenReturn("/saml-login");

        filter.doFilter(request, response, chain);

        verify(response).sendRedirect("/saml-login");
    }

    @Test
    void doFilter_providerFailure_leavesTheStockPage() throws Exception {
        // The stock login must keep working whatever happens
        when(provider.loginUrlFor(request, null)).thenThrow(new IllegalStateException("boom"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }
}
