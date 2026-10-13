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
import java.util.List;
import java.util.Locale;

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
 * (a {@code urlResolver} request attribute is present) — vanity/rewritten or
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
 * parameter, falling back to server-name resolution — no parameter is needed
 * as long as the virtual host (e.g. edit.jahia.com) is declared on the site.
 *
 * <p><strong>Per-site host scoping (fail-safe by default):</strong> each site holds its own list of
 * hosts ({@link SiteHostsStore}, one {@code karaf/etc} file per site, edited from the site's
 * administration). A request is routed by the site its host resolves to -- the same lookup the
 * SAML connect endpoint uses to pick its connector -- and only that site's list is consulted:
 * <ul>
 * <li><em>host in its site's list</em> -- straight redirect to the SAML connect endpoint (no login
 * form at all);</li>
 * <li><em>site configured, host NOT in its list</em> -- redirect to {@value #CHOICE_LOGIN_PATH}
 * (served by {@link SamlLoginPageFilter}): a page identical to the standard login form plus a
 * "Sign in with Google" button, so users choose between local credentials and Google SAML;</li>
 * <li><em>site not configured, or host resolving to no site</em> -- provider inactive: both methods
 * return {@code null} and Jahia serves the stock {@code /cms/login} page and default logout. This
 * protects against lockouts -- an unconfigured site must never have its login flow altered.</li>
 * </ul>
 * Because a list only ever applies to requests resolving to its own site, an administrator of one
 * site cannot change how any other site logs its visitors in.
 */
@Component(immediate = true, service = {LoginUrlProvider.class, LogoutUrlProvider.class})
public class SamlLoginUrlProvider implements LoginUrlProvider, LogoutUrlProvider {

    /** Choice login page (credentials or Google SAML) served by {@link SamlLoginPageFilter}. */
    public static final String CHOICE_LOGIN_PATH = "/saml-login";

    /** Connect endpoint of the saml-authentication-valve module (SAML2Filter). */
    private static final String SAML_CONNECT_PATH = "/home.connect.saml";

    /** Jahia logout servlet; honors a validated relative {@code redirect} parameter. */
    private static final String LOGOUT_SERVLET_PATH = "/cms/logout";

    /** Same cap core's ErrorServlet applies to its own return URLs. */
    private static final int MAX_REDIRECT_LENGTH = 2048;

    private static final Logger LOGGER = LoggerFactory.getLogger(SamlLoginUrlProvider.class);

    /** Where a request goes, decided by the site its host resolves to. */
    private enum Route { INACTIVE, SAML_CONNECT, CHOICE_PAGE }

    private final SiteHostsStore siteHostsStore;
    private final SiteResolver siteResolver;

    @Activate
    public SamlLoginUrlProvider(@Reference SiteHostsStore siteHostsStore, @Reference SiteResolver siteResolver) {
        this.siteHostsStore = siteHostsStore;
        this.siteResolver = siteResolver;
    }

    @Override
    public String getLoginUrl(HttpServletRequest request) {
        Route route = route(request);
        if (route == Route.INACTIVE) {
            // fail-safe default: null is load-bearing — LoginConfig filters
            // blank results and falls back to the standard /cms/login page
            return null;
        }
        // Listed host: straight SAML redirect. Other host of a configured site: choice page
        // (standard credentials form + "Sign in with Google" SAML button).
        String path = route == Route.SAML_CONNECT ? SAML_CONNECT_PATH : CHOICE_LOGIN_PATH;
        String loginUrl = request.getContextPath() + path;
        String originalUrl = resolveOriginalUrl(request);
        if (originalUrl == null) {
            return loginUrl;
        }
        return loginUrl + "?redirect=" + WebUtils.urlEncode(originalUrl);
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
        if (route(request) != Route.SAML_CONNECT) {
            return null;
        }
        // Send the user to a public landing page after logout. The default
        // behavior (redirect to referer) would hit a protected page, return a
        // 401 and silently re-login through SAML — logout would appear broken.
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
     * — request paths are restored to the original by the container. The
     * query string comes from {@code getQueryString()}: the Servlet spec
     * defines no {@code javax.servlet.error.query_string} attribute, and
     * Tomcat (the only container Jahia ships on) restores the original query
     * string during error dispatch — core's own ErrorServlet relies on the
     * same behavior;</li>
     * <li>url-rewrite forward: {@code javax.servlet.forward.request_uri} —
     * the pretty URL the browser sent, not the rewritten /cms/... one, with
     * the query from {@code javax.servlet.forward.query_string};</li>
     * <li>plain request: current URI and query string.</li>
     * </ol>
     * Returns {@code null} (no redirect parameter) for SAML endpoints — they
     * must never become a post-login target — and for oversized URLs.
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
        // Strip any query string a url-rewrite rule may have folded into the
        // URI attribute before the suffix check, so /home.connect.saml?x=y
        // still trips the SAML/choice-page loop guard.
        int queryStart = uri.indexOf('?');
        String uriPath = queryStart < 0 ? uri : uri.substring(0, queryStart);
        if (uriPath.endsWith(".saml") || uriPath.endsWith(CHOICE_LOGIN_PATH)) {
            return null;
        }
        String originalUrl = (query == null || query.isEmpty()) ? uri : uri + '?' + query;
        return originalUrl.length() > MAX_REDIRECT_LENGTH ? null : originalUrl;
    }

    /**
     * Routes by the site the host resolves to, consulting that site's list only. Any failure to
     * resolve degrades to {@link Route#INACTIVE}: the stock login must keep working whatever happens.
     */
    private Route route(HttpServletRequest request) {
        String serverName = request.getServerName();
        if (serverName == null) {
            return Route.INACTIVE;
        }
        String host = serverName.toLowerCase(Locale.ROOT);
        String siteKey;
        try {
            siteKey = siteResolver.siteKeyFor(host);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not resolve the site of host {}; leaving its login flow unchanged", host, e);
            return Route.INACTIVE;
        }
        if (siteKey == null) {
            return Route.INACTIVE;
        }
        List<String> siteHosts = siteHostsStore.hosts(siteKey);
        if (siteHosts.isEmpty()) {
            return Route.INACTIVE;
        }
        return siteHosts.contains(host) ? Route.SAML_CONNECT : Route.CHOICE_PAGE;
    }
}
