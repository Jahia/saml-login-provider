package org.jahia.community.modules.samlloginprovider;

import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.services.usermanager.JahiaUser;
import org.jahia.services.usermanager.JahiaUserManagerService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;

/**
 * Sends visitors of core's own login page ({@code GET /cms/login}) to this module's login wherever
 * {@link SamlLoginUrlProvider} acts, keeping the page they asked for.
 *
 * <p>Some core paths never reach the provider: Jahia protects {@code /tools/**},
 * {@code /modules/tools/**}, {@code /modules/**}{@code /*.jsp}, {@code *.raw} and
 * {@code /cms/serverStats} with Apache Shiro, whose login URL is hard-wired to {@code /cms/login}
 * in core's Spring configuration ({@code applicationcontext-security.xml}). Its
 * {@code JahiaAuthenticationFilter} redirects anonymous visitors to
 * {@code /cms/login?redirect=<uri>} directly, without the 401 error page -- the only place
 * {@code LoginConfig}, hence the provider, is consulted. Unauthenticated, {@code GET /cms/login}
 * then just shows the stock form. Catching that page covers every such path, and any hard-coded
 * link to it, in one place.
 *
 * <p>Only a plain visit is redirected. Everything the login servlet itself handles passes through
 * untouched: a user already logged in, credential posts (the choice page's own form posts here), a failed login shown with
 * {@code loginError}, a GET login with {@code username}, and REST mode. So does any request the
 * provider does not route, and any failure: the stock login must keep working whatever happens.
 */
@Component(immediate = true, service = AbstractServletFilter.class)
public class CmsLoginRedirectFilter extends AbstractServletFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(CmsLoginRedirectFilter.class);

    /** Login servlet parameters meaning "this is a login action, not a visit". */
    private static final List<String> LOGIN_ACTION_PARAMETERS =
            List.of("loginError", "username", "restMode", "redirectActive");

    /** Session attribute holding the logged-in user (core's JahiaAuthenticationFilter reads the same). */
    static final String SESSION_USER_ATTRIBUTE = "org.jahia.usermanager.jahiauser";

    /**
     * Static mandatory reference: DS binds it before {@link #activate()}, which publishes it safely to
     * request threads.
     */
    private SamlLoginUrlProvider loginUrlProvider;

    @Reference
    public void setLoginUrlProvider(SamlLoginUrlProvider loginUrlProvider) {
        this.loginUrlProvider = loginUrlProvider;
    }

    @Activate
    public void activate() {
        setMatchAllUrls(false);
        // Jahia matches "/x/*" as a prefix of the raw URI; an exact "/cms/login" would miss Shiro's
        // "/cms/login;jsessionid=..." form, so the path is checked in doFilter
        setUrlPatterns(new String[]{"/cms/*"});
        setFilterName("CmsLoginRedirectFilter");
        setOrder(-5.5f);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to initialize: configured at component activation
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String loginUrl = isPlainLoginPageVisit(httpRequest) ? moduleLoginUrl(httpRequest) : null;
        if (loginUrl == null) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.sendRedirect(httpResponse.encodeRedirectURL(loginUrl));
    }

    @Override
    public void destroy() {
        // nothing to clean up
    }

    private static boolean isPlainLoginPageVisit(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return false;
        }
        if (!SamlLoginUrlProvider.CORE_LOGIN_PATH.equals(pathWithinApplication(request))) {
            return false;
        }
        return LOGIN_ACTION_PARAMETERS.stream().noneMatch(name -> request.getParameter(name) != null)
                && !isLoggedIn(request);
    }

    /** A logged-in user asking for the login page gets the stock one, not a needless SSO round trip. */
    private static boolean isLoggedIn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object user = session == null ? null : session.getAttribute(SESSION_USER_ATTRIBUTE);
        return user instanceof JahiaUser && !JahiaUserManagerService.isGuest((JahiaUser) user);
    }

    /** The request path without the context path and without path parameters (";jsessionid=..."). */
    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (uri == null) {
            return "";
        }
        String path = contextPath != null && uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        int parameterStart = path.indexOf(';');
        return parameterStart < 0 ? path : path.substring(0, parameterStart);
    }

    private String moduleLoginUrl(HttpServletRequest request) {
        try {
            return loginUrlProvider.loginUrlFor(request, request.getParameter("redirect"));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not decide the login page of host {}; keeping the stock one", request.getServerName(), e);
            return null;
        }
    }
}
