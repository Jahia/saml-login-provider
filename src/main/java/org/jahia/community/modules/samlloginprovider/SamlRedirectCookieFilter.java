package org.jahia.community.modules.samlloginprovider;

import org.jahia.bin.filters.AbstractServletFilter;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Keeps the post-login target across the IdP round trip when the IdP is on another site (Google).
 *
 * <p>At connect time the SAML valve ({@code SAML2Util.storeAuthenticationContext}, 4.1.1) stores the
 * validated {@code redirect} parameter in a {@code redirect} cookie with no {@code SameSite}
 * attribute, and reads it back at {@code /home.callback.saml}. The IdP returns the user with a
 * cross-site POST, on which browsers do not send a cookie treated as {@code SameSite=Lax} -- the
 * browser default for a cookie without the attribute, and what a container-wide policy may force.
 * The valve then finds no cookie and falls back to the connector's {@code postLoginPath}: the user
 * lands on the site's home page instead of the page they asked for.
 *
 * <p>This filter runs just before the valve's {@code SAML2Filter} on its connect endpoint and writes
 * that one cookie itself with {@code SameSite=None; Secure}. Anything it cannot write safely is
 * left to the container as before: other cookies, plain-HTTP requests (browsers reject
 * {@code SameSite=None} without {@code Secure}), and values outside RFC 6265.
 *
 * <p>Relaxing SameSite is harmless for this cookie: it only holds a post-login target the valve
 * validated before setting it, and SameSite governs when a cookie is sent, not who may set it.
 */
@Component(immediate = true, service = AbstractServletFilter.class)
public class SamlRedirectCookieFilter extends AbstractServletFilter {

    /** The valve's cookie holding the post-login target. */
    static final String REDIRECT_COOKIE = "redirect";

    /** The valve's connect endpoint, which stores the redirect cookie. */
    private static final String CONNECT_SUFFIX = "connect.saml";

    /** Just before the valve's SAML2Filter (order -5.4; lower runs first). */
    private static final float ORDER_BEFORE_VALVE = -5.45f;

    /** RFC 6265 cookie-octet: what Tomcat's Rfc6265CookieProcessor accepts in a value. */
    private static final Pattern COOKIE_VALUE = Pattern.compile("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]*");
    /** Path and domain attribute values: no separators or controls. */
    private static final Pattern ATTRIBUTE_VALUE = Pattern.compile("[\\x21-\\x3A\\x3C-\\x7E]*");

    @Activate
    public void activate() {
        setMatchAllUrls(false);
        // Jahia, like the servlet spec, matches *.<ext> on the text after the LAST dot only, so the
        // valve's own pattern is used and the connect endpoint is picked in doFilter
        setUrlPatterns(new String[]{"*.saml"});
        setFilterName("SamlRedirectCookieFilter");
        setOrder(ORDER_BEFORE_VALVE);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to initialize: configured at component activation
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String uri = httpRequest.getRequestURI();
        // The valve dispatches on the same suffix (SAML2Filter.doFilter)
        if (uri == null || !uri.endsWith(CONNECT_SUFFIX)) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(request, new RedirectCookieResponse((HttpServletResponse) response, isHttps(httpRequest)));
    }

    @Override
    public void destroy() {
        // nothing to clean up
    }

    /**
     * HTTPS as the browser sees it: TLS may end at a proxy, in which case Tomcat sees http. A forged
     * {@code X-Forwarded-Proto} only marks the cookie Secure on an http connection, where the browser
     * then drops it -- the same outcome as without this filter.
     */
    private static boolean isHttps(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-Proto");
        if (forwarded == null) {
            return false;
        }
        // Chained proxies append theirs: the first entry is the scheme the browser used
        int comma = forwarded.indexOf(',');
        String clientScheme = comma < 0 ? forwarded : forwarded.substring(0, comma);
        return "https".equalsIgnoreCase(clientScheme.trim());
    }

    /** Writes the valve's redirect cookie with SameSite=None; delegates everything else. */
    private static final class RedirectCookieResponse extends HttpServletResponseWrapper {

        private final boolean https;

        private RedirectCookieResponse(HttpServletResponse response, boolean https) {
            super(response);
            this.https = https;
        }

        @Override
        public void addCookie(Cookie cookie) {
            if (!https || !REDIRECT_COOKIE.equals(cookie.getName()) || !isWritable(cookie)) {
                super.addCookie(cookie);
                return;
            }
            addHeader("Set-Cookie", toHeader(cookie));
        }

        private static boolean isWritable(Cookie cookie) {
            String value = cookie.getValue() == null ? "" : cookie.getValue();
            return COOKIE_VALUE.matcher(value).matches()
                    && (cookie.getPath() == null || ATTRIBUTE_VALUE.matcher(cookie.getPath()).matches())
                    && (cookie.getDomain() == null || ATTRIBUTE_VALUE.matcher(cookie.getDomain()).matches());
        }

        private static String toHeader(Cookie cookie) {
            StringBuilder header = new StringBuilder(cookie.getName()).append('=')
                    .append(cookie.getValue() == null ? "" : cookie.getValue());
            if (cookie.getPath() != null) {
                header.append("; Path=").append(cookie.getPath());
            }
            if (cookie.getDomain() != null) {
                header.append("; Domain=").append(cookie.getDomain());
            }
            if (cookie.getMaxAge() >= 0) {
                header.append("; Max-Age=").append(cookie.getMaxAge());
            }
            // SameSite=None is only honored with Secure; this response is HTTPS for the browser
            header.append("; Secure");
            if (cookie.isHttpOnly()) {
                header.append("; HttpOnly");
            }
            return header.append("; SameSite=None").toString();
        }
    }
}
