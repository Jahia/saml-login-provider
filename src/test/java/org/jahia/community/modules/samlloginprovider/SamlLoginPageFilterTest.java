package org.jahia.community.modules.samlloginprovider;

import org.jahia.utils.i18n.ResourceBundles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginPageFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain chain;

    private SamlLoginPageFilter filter;

    @BeforeEach
    void setUp() throws IOException {
        filter = new SamlLoginPageFilter();
        // loads saml-login-page/login.html from the (real) bundle resources on the test classpath
        filter.activate();
    }

    @Test
    void activate_loadsTemplateAndConfiguresFilterMetadata() throws IOException {
        // Arrange
        SamlLoginPageFilter freshFilter = new SamlLoginPageFilter();

        // Act / Assert: activation must not throw and must configure the filter as documented
        freshFilter.activate();
        assertThat(freshFilter.isMatchAllUrls()).isFalse();
        assertThat(freshFilter.getUrlPatterns()).containsExactly(SamlLoginUrlProvider.CHOICE_LOGIN_PATH);
        assertThat(freshFilter.getFilterName()).isEqualTo("SamlLoginPageFilter");
        assertThat(freshFilter.getOrder()).isEqualTo(-5.5f);
    }

    @Test
    void init_and_destroy_areNoOpsAndNeverThrow() {
        // Act / Assert: both are documented no-ops (template is loaded once at @Activate)
        assertThatCode(() -> filter.init((javax.servlet.FilterConfig) null)).doesNotThrowAnyException();
        assertThatCode(() -> filter.destroy()).doesNotThrowAnyException();
    }

    @Test
    void doFilter_getMethod_returns200WithRenderedTemplateAndSecurityHeaders() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("/mySite", Locale.ENGLISH);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        verify(response, never()).sendError(anyInt());
        verify(response).setContentType("text/html;charset=UTF-8");
        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).setHeader("X-Frame-Options", "DENY");
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(response).setHeader(eq("Content-Security-Policy"), contains("default-src 'none'"));
        String rendered = body.toString();
        assertThat(rendered).contains("<html lang=\"en\">");
        assertThat(rendered).contains("action=\"/mySite/cms/login\"");
        assertThat(rendered).contains("action=\"/mySite/home.connect.saml\"");
        assertThat(rendered).doesNotContain("__");
    }

    @Test
    void doFilter_nonGetMethod_returns405AndNeverRenders() throws Exception {
        // Arrange
        when(request.getMethod()).thenReturn("POST");

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        verify(response).sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        verify(response, never()).getWriter();
        verify(response, never()).setContentType(anyString());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void doFilter_validRelativeRedirect_isRenderedInHiddenFields() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("redirect")).thenReturn("/some/path?x=1");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).contains("name=\"redirect\" value=\"/some/path?x=1\"");
    }

    @Test
    void doFilter_absoluteRedirect_isRejectedAndOmitted() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("redirect")).thenReturn("https://evil.example.com/phish");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("name=\"redirect\"");
        assertThat(body.toString()).doesNotContain("evil.example.com");
    }

    @Test
    void doFilter_protocolRelativeRedirect_isRejectedAndOmitted() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("redirect")).thenReturn("//evil.example.com/phish");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("name=\"redirect\"");
        assertThat(body.toString()).doesNotContain("evil.example.com");
    }

    @Test
    void doFilter_redirectWithControlCharacter_isRejectedAndOmitted() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("redirect")).thenReturn("/foo\nbar");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("name=\"redirect\"");
    }

    @Test
    void doFilter_redirectWithColon_isRejectedAndOmitted() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("redirect")).thenReturn("/foo:bar");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("name=\"redirect\"");
    }

    @Test
    void doFilter_redirectWithBackslash_isRejectedAndOmitted() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("redirect")).thenReturn("/foo\\bar");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("name=\"redirect\"");
    }

    @Test
    void doFilter_redirectContainingMarkupAndQuote_isHtmlEscapedNotRaw() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        // relative, no colon/backslash/control char -> passes safeRelativeRedirect, then must be escaped
        when(request.getParameter("redirect")).thenReturn("/page?x=<script>\"</script>&y='z'");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        String rendered = body.toString();
        assertThat(rendered).doesNotContain("<script>");
        assertThat(rendered).contains("&lt;script&gt;&quot;&lt;/script&gt;&amp;y=&#39;z&#39;");
    }

    @Test
    void doFilter_invalidSiteParameter_isOmittedFromOutput() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("site")).thenReturn("<script>alert(1)</script>");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("name=\"site\"");
        assertThat(body.toString()).doesNotContain("<script>");
    }

    @Test
    void doFilter_validSiteParameter_isRenderedAsHiddenField() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("site")).thenReturn("mySite-2");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).contains("name=\"site\" value=\"mySite-2\"");
    }

    @Test
    void doFilter_loginErrorParameter_isNeverEchoedRaw() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("loginError")).thenReturn("<injected>");
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("<injected>");
        assertThat(body.toString()).contains("class=\"login-error\" role=\"alert\"");
    }

    @Test
    void doFilter_loginErrorCodes_mapToDistinctMessageKeys() throws Exception {
        // Arrange: stub the internal resource bundle so each key echoes its own name,
        // proving the loginError code selects the expected message key.
        ResourceBundle echoingBundle = new java.util.ListResourceBundle() {
            @Override
            protected Object[][] getContents() {
                return new Object[][]{
                        {"message.accountLocked", "[message.accountLocked]"},
                        {"message.loggedInUsersLimitReached", "[message.loggedInUsersLimitReached]"},
                        {"message.invalidUsernamePassword", "[message.invalidUsernamePassword]"},
                        {"label.login", "Login"},
                        {"label.loginErrorTitle", "Login error"},
                        {"label.username", "Username"},
                        {"label.password", "Password"},
                        {"label.rememberme", "Remember me"},
                        {"label.or", "or"},
                        {"label.samlLogin", "Sign in with Google"},
                };
            }
        };

        try (MockedStatic<ResourceBundles> resourceBundles = mockStatic(ResourceBundles.class)) {
            resourceBundles.when(() -> ResourceBundles.get(eq("JahiaInternalResources"), any(Locale.class)))
                    .thenReturn(echoingBundle);

            assertLoginErrorMapsToMessage("account_locked", "[message.accountLocked]");
            assertLoginErrorMapsToMessage("logged_in_users_limit_reached", "[message.loggedInUsersLimitReached]");
            assertLoginErrorMapsToMessage("some_unknown_code", "[message.invalidUsernamePassword]");
        }
    }

    private void assertLoginErrorMapsToMessage(String loginErrorCode, String expectedMessage) throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(request.getParameter("loginError")).thenReturn(loginErrorCode);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).contains(expectedMessage);
    }

    @Test
    void doFilter_noLoginError_rendersNoErrorBlock() throws Exception {
        // Arrange
        StringWriter body = new StringWriter();
        givenBasicGetRequest("", Locale.ENGLISH);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        // Act
        filter.doFilter(request, response, chain);

        // Assert
        assertThat(body.toString()).doesNotContain("login-error");
    }

    private void givenBasicGetRequest(String contextPath, Locale locale) {
        when(request.getMethod()).thenReturn("GET");
        when(request.getContextPath()).thenReturn(contextPath);
        when(request.getLocale()).thenReturn(locale);
        // Stub every request parameter renderPage() reads to null by default (strict stubs
        // reject an unstubbed getParameter(...) once another argument of the same method
        // has been stubbed); individual tests override the one they care about afterwards.
        when(request.getParameter("redirect")).thenReturn(null);
        when(request.getParameter("loginError")).thenReturn(null);
        when(request.getParameter("site")).thenReturn(null);
    }
}
