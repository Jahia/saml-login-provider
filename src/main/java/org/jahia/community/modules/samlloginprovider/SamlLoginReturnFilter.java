package org.jahia.community.modules.samlloginprovider;

import org.jahia.bin.filters.AbstractServletFilter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Serves {@value SamlLoginUrlProvider#RETURN_PATH}: where a SAML login returns before going on to
 * the page the visitor asked for.
 *
 * <p>The SAML valve's callback only stores the IdP profile in the session; the login completes on
 * the NEXT request, when Jahia's auth pipeline runs and, as session-fixation protection, invalidates
 * the session. For most URLs core's {@code JcrSessionFilter} (order -1) runs that pipeline and
 * handles the invalidation. But on a URL core guards with Shiro -- {@code /tools/**},
 * {@code /modules/tools/**}, ... -- the Shiro filter (order -3) runs first and runs the pipeline
 * itself ({@code JahiaAuthenticationFilter.onAccessDenied}), then logs in a Shiro subject still bound
 * to the session it has just invalidated: {@code InvalidSessionException}, a 500, until the visitor
 * reloads the page.
 *
 * <p>This hop is guarded by nothing, and runs after {@code JcrSessionFilter}: the login completes
 * here the normal way, then the visitor goes on, already logged in. The target is untrusted (anyone
 * can craft this URL): anything but a plain relative path that is not a login endpoint sends the
 * visitor home instead.
 */
@Component(immediate = true, service = AbstractServletFilter.class)
public class SamlLoginReturnFilter extends AbstractServletFilter {

    /** After core's JcrSessionFilter (-1, completes the login), before its UrlRewriteFilter (1). */
    private static final float ORDER_AFTER_LOGIN = 0f;

    @Activate
    public void activate() {
        setMatchAllUrls(false);
        setUrlPatterns(new String[]{SamlLoginUrlProvider.RETURN_PATH});
        setFilterName("SamlLoginReturnFilter");
        setOrder(ORDER_AFTER_LOGIN);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to initialize: configured at component activation
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String target = httpRequest.getParameter("redirect");
        String destination = SamlLoginUrlProvider.isSafeTarget(target) ? target : httpRequest.getContextPath() + "/";
        // Not encodeRedirectURL: Shiro rejects ";" on the paths it guards (blockSemicolon), so a
        // "/tools;jsessionid=..." target would fail. The session travels in its cookie.
        httpResponse.sendRedirect(destination);
    }

    @Override
    public void destroy() {
        // nothing to clean up
    }
}
