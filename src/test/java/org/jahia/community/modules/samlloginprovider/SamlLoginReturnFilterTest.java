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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginReturnFilterTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private SamlLoginReturnFilter filter;

    @BeforeEach
    void setUp() {
        filter = new SamlLoginReturnFilter();
        filter.activate();
        lenient().when(request.getContextPath()).thenReturn("/ctx");
        // A container would add ";jsessionid=" here for a cookie-less client: see the test below
        lenient().when(response.encodeRedirectURL(anyString())).thenAnswer(i -> i.getArgument(0) + ";jsessionid=X");
    }

    @Test
    void activate_servesTheHop_afterCoresLoginFilters() {
        assertThat(filter.getUrlPatterns()).containsExactly(SamlLoginUrlProvider.RETURN_PATH);
        // JcrSessionFilter (order -1) completes the login before this runs; Shiro is -3
        assertThat(filter.getOrder()).isGreaterThan(-1f);
    }

    @Test
    void doFilter_sendsTheVisitorOnToTheirTarget() throws Exception {
        // The valve appends ?site= / &site= to the target it returns to
        when(request.getParameter("redirect")).thenReturn("/ctx/tools");

        filter.doFilter(request, response, chain);

        verify(response).sendRedirect("/ctx/tools");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_neverRewritesTheSessionIntoTheUrl() throws Exception {
        // Shiro rejects ";" on the paths it guards (InvalidRequestFilter blockSemicolon): a
        // "/tools;jsessionid=..." target would be a 400. The session travels in its cookie.
        when(request.getParameter("redirect")).thenReturn("/ctx/tools");

        filter.doFilter(request, response, chain);

        verify(response).sendRedirect("/ctx/tools");
        verify(response, never()).encodeRedirectURL(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"//evil.example.com/", "https://evil.example.com/", "/\\evil", "/saml-login-return",
            "/home.connect.saml", "/cms/login"})
    void doFilter_unsafeOrLoopingTarget_goesHome(String target) throws Exception {
        // Anyone can craft this URL: never an open redirect, never a loop
        when(request.getParameter("redirect")).thenReturn(target);

        filter.doFilter(request, response, chain);

        verify(response).sendRedirect("/ctx/");
    }

    @Test
    void doFilter_noTarget_goesHome() throws Exception {
        when(request.getParameter("redirect")).thenReturn(null);

        filter.doFilter(request, response, chain);

        verify(response).sendRedirect("/ctx/");
    }
}
