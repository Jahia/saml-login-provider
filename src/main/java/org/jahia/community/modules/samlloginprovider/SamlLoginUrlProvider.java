package org.jahia.community.modules.samlloginprovider;

import org.jahia.params.valves.LoginUrlProvider;
import org.jahia.params.valves.LogoutUrlProvider;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Replaces the Jahia login page with the SAML connect endpoint provided by the
 * saml-authentication-valve module, so users authenticated upstream (Google /
 * gate) are logged into Jahia without seeing a second login form.
 *
 * <p>How it is wired (verified against Jahia 8.2.3.0 core sources):
 * <ul>
 * <li>This DS component is published as {@code LoginUrlProvider} /
 * {@code LogoutUrlProvider} OSGi services and bound into
 * {@code LoginConfig} / {@code LogoutConfig} by {@code OSGIRegistry}.</li>
 * <li>On a 401, {@code ErrorServlet} redirects to {@link #getLoginUrl}. The
 * provider appends {@code redirect=<encoded original URL>} itself because
 * core only does so when the request went through the Render servlet
 * (a {@code urlResolver} request attribute is present): vanity/rewritten or
 * non-render URLs would otherwise lose their return target and land on the
 * SAML valve's default {@code postLoginPath}. When core does append its own
 * {@code &redirect=...} too, the duplicate is harmless: the valve reads the
 * first occurrence (ours).</li>
 * <li>The SAML valve validates the parameter against open redirects
 * ({@code SAML2Util.isAuthorizedRedirect}), stores it in a cookie at connect
 * time and sends the user back there after the IdP callback.</li>
 * <li>{@code ${url.login}} in templates resolves to the same URL, so login
 * links also trigger the SAML flow directly.</li>
 * </ul>
 *
 * <p>The connect endpoint resolves the site from the {@code siteKey} request
 * parameter, falling back to server-name resolution: no parameter is needed
 * as long as the virtual host (e.g. edit.jahia.com) is declared on the site.
 *
 * <p><strong>Per-site behavior (fail-safe by default):</strong> a request is routed by the site its
 * host resolves to -- the same lookup the SAML connect endpoint uses to pick its connector. The
 * module only acts on a site where it is <em>enabled</em> and that has <em>SAML authentication set
 * up</em> (an enabled saml-authentication-valve connector, see {@link SamlSetup}). On such a site:
 * <ul>
 * <li><em>host in the site's list</em> ({@link SiteHostsStore}) -- straight redirect to the SAML
 * connect endpoint (no login form at all);</li>
 * <li><em>any other host of the site, including when the site has no list</em> -- redirect to
 * {@value #CHOICE_LOGIN_PATH} (served by {@link SamlLoginPageFilter}): the standard login form plus
 * a "Sign in with Google" button, so users choose between local credentials and SAML.</li>
 * </ul>
 * A host that NO site declares (typically the editing host) is governed by systemsite the same way;
 * its SAML URL then names {@code siteKey=systemsite}, since the valve cannot find a site from such a
 * host. Everywhere else -- module not enabled, no SAML connector, or a host shared by several sites --
 * both methods return {@code null} and Jahia serves the stock {@code /cms/login} page and default
 * logout. Requiring the connector means nobody is ever sent to a SAML endpoint that cannot serve
 * them, and no Google button is offered that would lead to a 400.
 *
 * <p>Only the request's own site is consulted, so an administrator of one site cannot change how
 * any other site logs its visitors in.
 */
@Component(immediate = true,
        service = {LoginUrlProvider.class, LogoutUrlProvider.class, SamlLoginUrlProvider.class})
public class SamlLoginUrlProvider implements LoginUrlProvider, LogoutUrlProvider {

    /** Choice login page (credentials or Google SAML) served by {@link SamlLoginPageFilter}. */
    public static final String CHOICE_LOGIN_PATH = "/saml-login";

    /** Connect endpoint of the saml-authentication-valve module (SAML2Filter). */
    private static final String SAML_CONNECT_PATH = "/home.connect.saml";

    /** Jahia logout servlet; honors a validated relative {@code redirect} parameter. */
    private static final String LOGOUT_SERVLET_PATH = "/cms/logout";

    /**
     * Where a SAML login returns before its target, served by {@link SamlLoginReturnFilter}: the
     * first request after the callback completes the login, and on a Shiro-guarded target core fails
     * it (see that filter).
     */
    public static final String RETURN_PATH = "/saml-login-return";

    /** Jahia's own login page, which some core paths redirect to directly (see {@link CmsLoginRedirectFilter}). */
    static final String CORE_LOGIN_PATH = "/cms/login";

    /** Same cap core's ErrorServlet applies to its own return URLs. */
    private static final int MAX_REDIRECT_LENGTH = 2048;

    /** Printable ASCII without a backslash: what a relative post-login target may contain. */
    private static final Pattern SAFE_TARGET_CHARS = Pattern.compile("[\\x21-\\x5B\\x5D-\\x7E]*");

    private static final Logger LOGGER = LoggerFactory.getLogger(SamlLoginUrlProvider.class);

    /** Where a request goes, decided by the site its host resolves to. */
    private enum Destination { INACTIVE, SAML_CONNECT, CHOICE_PAGE }

    /** The destination, and the site to name to the valve ({@code null}: it resolves the host). */
    private static final class Route {
        private static final Route INACTIVE = new Route(Destination.INACTIVE, null);

        private final Destination destination;
        private final String explicitSiteKey;

        private Route(Destination destination, String explicitSiteKey) {
            this.destination = destination;
            this.explicitSiteKey = explicitSiteKey;
        }
    }

    private final SiteHostsStore siteHostsStore;
    private final SiteResolver siteResolver;
    private final SamlSetup samlSetup;

    @Activate
    public SamlLoginUrlProvider(@Reference SiteHostsStore siteHostsStore, @Reference SiteResolver siteResolver,
                                @Reference SamlSetup samlSetup) {
        this.siteHostsStore = siteHostsStore;
        this.siteResolver = siteResolver;
        this.samlSetup = samlSetup;
    }

    @Override
    public String getLoginUrl(HttpServletRequest request) {
        return loginUrl(request, resolveOriginalUrl(request));
    }

    /**
     * The login URL for a request that already names its post-login target -- core's own login page,
     * reached as {@code /cms/login?redirect=...} (see {@link CmsLoginRedirectFilter}) -- or {@code null}
     * where the module leaves the login alone. The target is untrusted input: anything but a plain
     * relative path, or a login endpoint (a loop), is dropped rather than passed on.
     */
    public String loginUrlFor(HttpServletRequest request, String target) {
        return loginUrl(request, isSafeTarget(target) ? target : null);
    }

    private String loginUrl(HttpServletRequest request, String originalUrl) {
        Route route = route(request);
        if (route.destination == Destination.INACTIVE) {
            // fail-safe default: null is load-bearing: LoginConfig filters
            // blank results and falls back to the standard /cms/login page
            return null;
        }
        // Listed host: straight SAML redirect. Any other host of the site: choice page
        // (standard credentials form + "Sign in with Google" SAML button).
        boolean connect = route.destination == Destination.SAML_CONNECT;
        StringBuilder loginUrl = new StringBuilder(request.getContextPath())
                .append(connect ? SAML_CONNECT_PATH : CHOICE_LOGIN_PATH);
        char separator = '?';
        if (connect && route.explicitSiteKey != null) {
            // The choice page names the site in its own Google form (see SamlLoginPageFilter)
            loginUrl.append(separator).append("siteKey=").append(WebUtils.urlEncode(route.explicitSiteKey));
            separator = '&';
        }
        if (originalUrl != null) {
            // Only the SAML path needs the hop; the choice page wraps it for its own Google form
            String target = connect ? returnHop(request.getContextPath(), originalUrl) : originalUrl;
            loginUrl.append(separator).append("redirect=").append(WebUtils.urlEncode(target));
        }
        return loginUrl.toString();
    }

    @Override
    public boolean hasCustomLoginUrl() {
        // Always true: this method has no request argument, so per-host
        // filtering must happen in getLoginUrl(), which returns null off-host.
        return true;
    }

    @Override
    public String getLogoutUrl(HttpServletRequest request) {
        // CAUTION (core asymmetry): unlike LoginConfig, LogoutConfig only ever
        // consults the FIRST bound LogoutUrlProvider. Deploying a second
        // LogoutUrlProvider on the same platform makes precedence dependent on
        // OSGi bind order, and our off-host null would suppress it. This
        // module assumes it is the only LogoutUrlProvider deployed.
        if (route(request).destination != Destination.SAML_CONNECT) {
            return null;
        }
        // Send the user to a public landing page after logout. The default
        // behavior (redirect to referer) would hit a protected page, return a
        // 401 and silently re-login through SAML, and logout would appear broken.
        String contextPath = request.getContextPath();
        return contextPath + LOGOUT_SERVLET_PATH + "?redirect=" + WebUtils.urlEncode(contextPath + "/");
    }

    @Override
    public boolean hasCustomLogoutUrl() {
        return true;
    }

    /**
     * Rebuilds the URL the user originally asked for, so the SAML valve can
     * send them back there after authentication. Resolution order:
     * <ol>
     * <li>error dispatch (401 page): {@code javax.servlet.error.request_uri}
     * (the container restores request paths to the original). The
     * query string comes from {@code getQueryString()}: the Servlet spec
     * defines no {@code javax.servlet.error.query_string} attribute, and
     * Tomcat (the only container Jahia ships on) restores the original query
     * string during error dispatch, and core's own ErrorServlet relies on the
     * same behavior;</li>
     * <li>url-rewrite forward: {@code javax.servlet.forward.request_uri}:
     * the pretty URL the browser sent, not the rewritten /cms/... one, with
     * the query from {@code javax.servlet.forward.query_string};</li>
     * <li>plain request: current URI and query string.</li>
     * </ol>
     * Returns {@code null} (no redirect parameter) for SAML endpoints (they
     * must never become a post-login target) and for oversized URLs.
     */
    private static String resolveOriginalUrl(HttpServletRequest request) {
        String uri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String query;
        if (uri != null) {
            query = request.getQueryString();
        } else {
            uri = (String) request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
            if (uri != null) {
                query = (String) request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING);
            } else {
                uri = request.getRequestURI();
                query = request.getQueryString();
            }
        }
        if (uri == null) {
            return null;
        }
        if (isLoginEndpoint(uri)) {
            return null;
        }
        String originalUrl = (query == null || query.isEmpty()) ? uri : uri + '?' + query;
        return originalUrl.length() > MAX_REDIRECT_LENGTH ? null : originalUrl;
    }

    /** The login hop returning to {@code target} (a path that already includes the context path). */
    public static String returnHop(String contextPath, String target) {
        return (contextPath == null ? "" : contextPath) + RETURN_PATH + "?redirect=" + WebUtils.urlEncode(target);
    }

    /** A plain relative path that is not a login endpoint: safe to send a visitor to. */
    static boolean isSafeTarget(String target) {
        return target != null && target.length() <= MAX_REDIRECT_LENGTH
                && target.startsWith("/") && !target.startsWith("//")
                && SAFE_TARGET_CHARS.matcher(target).matches()
                && !hasAmbiguousPath(target)
                && !isLoginEndpoint(target);
    }

    /**
     * A fragment, or an empty, "." or ".." segment: the container would normalize such a path, so the
     * login-endpoint guard below could not see what it really points at -- it is dropped instead.
     */
    private static boolean hasAmbiguousPath(String target) {
        int queryStart = target.indexOf('?');
        String path = queryStart < 0 ? target : target.substring(0, queryStart);
        return target.indexOf('#') >= 0 || path.contains("//") || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..");
    }

    /**
     * True for the SAML endpoints and the login pages, which must never become a post-login target
     * (loop). Any query string a url-rewrite rule may have folded into a URI attribute, and any path
     * parameter (Shiro's {@code ;jsessionid=}), is stripped first, so /home.connect.saml?x=y still
     * trips the guard.
     */
    private static boolean isLoginEndpoint(String uri) {
        int queryStart = uri.indexOf('?');
        String path = queryStart < 0 ? uri : uri.substring(0, queryStart);
        int parameterStart = path.indexOf(';');
        if (parameterStart >= 0) {
            path = path.substring(0, parameterStart);
        }
        return path.endsWith(".saml") || path.endsWith(CHOICE_LOGIN_PATH) || path.endsWith(CORE_LOGIN_PATH)
                || path.endsWith(RETURN_PATH);
    }

    /**
     * Routes by the site governing the host ({@link LoginSite}), consulting that site only. Any
     * failure degrades to {@link Route#INACTIVE}: the stock login must keep working whatever happens.
     */
    private Route route(HttpServletRequest request) {
        String serverName = request.getServerName();
        if (serverName == null) {
            return Route.INACTIVE;
        }
        String host = serverName.toLowerCase(Locale.ROOT);
        try {
            LoginSite site = LoginSite.of(siteResolver, host);
            if (site == null) {
                return Route.INACTIVE;
            }
            String siteKey = site.siteKey();
            if (!siteResolver.isModuleEnabled(siteKey) || !samlSetup.isConfigured(siteKey)) {
                return Route.INACTIVE;
            }
            Destination destination = siteHostsStore.hosts(siteKey).contains(host)
                    ? Destination.SAML_CONNECT : Destination.CHOICE_PAGE;
            return new Route(destination, site.isFallback() ? siteKey : null);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not decide the login route of host {}; leaving its login flow unchanged", host, e);
            return Route.INACTIVE;
        }
    }
}
