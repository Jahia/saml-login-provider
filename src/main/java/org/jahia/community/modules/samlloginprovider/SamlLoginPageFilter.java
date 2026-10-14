package org.jahia.community.modules.samlloginprovider;

import org.jahia.bin.filters.AbstractServletFilter;
import org.jahia.utils.i18n.ResourceBundles;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves {@code /saml-login}: a page visually identical to Jahia's standard
 * login page ({@code errors/error_401.jsp} — same CSS and markup) with an
 * additional "Sign in with Google" button that launches the SAML flow
 * ({@code /home.connect.saml}).
 *
 * <p>{@link SamlLoginUrlProvider} sends users here on hosts that are
 * <em>not</em> in the configured FQDN list (while configured hosts are
 * redirected straight to the SAML connect endpoint): users keep the choice
 * between local credentials and Google SAML.
 *
 * <p>Form contract (mirrors the {@code ui:loginArea} tag): POST
 * {@code /cms/login} with {@code username}, {@code password},
 * {@code useCookie}, hidden {@code site}/{@code redirect} and a
 * {@code failureRedirect} pointing back to this page — failed logins return
 * here with a {@code loginError} parameter (codes from {@code org.jahia.bin.Login}).
 */
@Component(immediate = true, service = AbstractServletFilter.class)
public class SamlLoginPageFilter extends AbstractServletFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamlLoginPageFilter.class);

    /** Matches every {@code __TOKEN__} placeholder so substitution is single-pass. */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("__[A-Z_]+__");

    /** The post-login target request parameter, shared by both forms. */
    private static final String REDIRECT_PARAM = "redirect";

    /**
     * Loaded once at activation, then only read. Marked {@code volatile} to make
     * the write-in-{@code activate} / read-in-request-threads handoff explicit.
     */
    private volatile String template;

    /**
     * Static mandatory reference: DS binds it before {@link #activate()}, which publishes it safely to
     * request threads. {@code null} only in tests that do not set it -- then no site is named.
     */
    private SiteResolver siteResolver;

    /** Same publication as {@link #siteResolver}; {@code null} only in tests -- then the default label. */
    private SiteHostsStore siteHostsStore;

    @Reference
    public void setSiteResolver(SiteResolver siteResolver) {
        this.siteResolver = siteResolver;
    }

    @Reference
    public void setSiteHostsStore(SiteHostsStore siteHostsStore) {
        this.siteHostsStore = siteHostsStore;
    }

    @Activate
    public void activate() throws IOException {
        setMatchAllUrls(false);
        setUrlPatterns(new String[]{SamlLoginUrlProvider.CHOICE_LOGIN_PATH});
        setFilterName("SamlLoginPageFilter");
        setOrder(-5.5f);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("saml-login-page/login.html")) {
            if (in == null) {
                throw new IOException("saml-login-page/login.html template not found in bundle");
            }
            template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // nothing to initialize: the template is loaded at component activation
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (!"GET".equals(httpRequest.getMethod())) {
            httpResponse.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }

        httpResponse.setContentType("text/html;charset=UTF-8");
        // never cache a login page
        httpResponse.setHeader("Cache-Control", "no-store");
        // harden the credential form: no framing (clickjacking), no sniffing, tight CSP
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("Content-Security-Policy",
                "default-src 'none'; style-src 'self' 'unsafe-inline'; img-src 'self'; "
                        + "font-src 'self'; form-action 'self'; base-uri 'self'; frame-ancestors 'none'");

        PrintWriter writer = httpResponse.getWriter();
        writer.write(renderPage(httpRequest));
        writer.flush();
    }

    @Override
    public void destroy() {
        // nothing to clean up
    }

    private String renderPage(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        Locale locale = request.getLocale();
        String redirect = safeRelativeRedirect(request.getParameter(REDIRECT_PARAM));
        String loginError = request.getParameter("loginError");
        String loginLabel = label(locale, "label.login", "Login");

        Map<String, String> tokens = new HashMap<>();
        tokens.put("__LANG__", escapeHtml(locale.toLanguageTag()));
        tokens.put("__CTX__", escapeHtml(contextPath));
        tokens.put("__ERROR_BLOCK__", errorBlock(loginError, locale));
        tokens.put("__FIELD_ERROR_ATTRS__", fieldErrorAttrs(loginError));
        tokens.put("__SITE_FIELD__", siteField(request.getParameter("site")));
        tokens.put("__REDIRECT_FIELD__", hiddenField(REDIRECT_PARAM, redirect));
        // The SAML login returns through the hop; the credentials form logs in within /cms/login itself
        tokens.put("__GOOGLE_REDIRECT_FIELD__", hiddenField(REDIRECT_PARAM,
                redirect.isEmpty() ? redirect : SamlLoginUrlProvider.returnHop(contextPath, redirect)));
        tokens.put("__GOOGLE_SITE_FIELD__", googleSiteField(request));
        tokens.put("__FAILURE_REDIRECT_FIELD__", hiddenField("failureRedirect", failureRedirect(contextPath, redirect)));
        tokens.put("__LABEL_TITLE__", loginError == null
                ? loginLabel
                : label(locale, "label.loginErrorTitle", "Login — authentication failed"));
        tokens.put("__LABEL_USERNAME__", label(locale, "label.username", "Username"));
        tokens.put("__LABEL_PASSWORD__", label(locale, "label.password", "Password"));
        tokens.put("__LABEL_REMEMBERME__", label(locale, "label.rememberme", "Remember me"));
        tokens.put("__LABEL_LOGIN__", loginLabel);
        tokens.put("__LABEL_OR__", label(locale, "label.or", "or"));
        String customLabel = customButtonLabel(request);
        tokens.put("__LABEL_GOOGLE__", customLabel.isEmpty()
                ? label(locale, "label.samlLogin", "Sign in with Google")
                : escapeHtml(customLabel));

        return substitute(template, tokens);
    }

    /**
     * Single-pass placeholder substitution: each {@code __TOKEN__} is replaced
     * exactly once, so a value that itself contains a {@code __TOKEN__}-shaped
     * string (e.g. a crafted {@code site} parameter) can never be re-expanded by
     * a later replacement. Unknown tokens collapse to an empty string.
     */
    private static String substitute(String source, Map<String, String> tokens) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(source);
        StringBuilder out = new StringBuilder(source.length());
        while (matcher.find()) {
            String replacement = tokens.getOrDefault(matcher.group(), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Only relative same-host paths are accepted as post-login targets —
     * the same policy as {@code Login.isAuthorizedRedirect} for relative URLs.
     */
    private static String safeRelativeRedirect(String redirect) {
        if (redirect == null || redirect.isEmpty()) {
            return "";
        }
        boolean isRelativePath = redirect.startsWith("/") && !redirect.startsWith("//");
        if (!isRelativePath || redirect.contains(":") || redirect.contains("\\")
                || redirect.chars().anyMatch(Character::isISOControl)) {
            return "";
        }
        return redirect;
    }

    private static String failureRedirect(String contextPath, String redirect) {
        String base = contextPath + SamlLoginUrlProvider.CHOICE_LOGIN_PATH;
        if (redirect.isEmpty()) {
            return base;
        }
        return base + "?redirect=" + WebUtils.urlEncode(redirect);
    }

    /**
     * Maps the {@code loginError} codes appended by {@code org.jahia.bin.Login}
     * to the same localized messages as the standard page. The submitted code
     * is never echoed back.
     */
    private static String errorBlock(String loginError, Locale locale) {
        if (loginError == null) {
            return "";
        }
        String messageKey;
        if ("account_locked".equals(loginError)) {
            messageKey = "message.accountLocked";
        } else if ("logged_in_users_limit_reached".equals(loginError)) {
            messageKey = "message.loggedInUsersLimitReached";
        } else {
            messageKey = "message.invalidUsernamePassword";
        }
        // id="login-error" is referenced by aria-describedby on the username/password inputs
        // (see fieldErrorAttrs); role="alert" so screen readers announce the error on this
        // server-rendered reload
        return "<div id=\"login-error\" class=\"login-error\" role=\"alert\">"
                + label(locale, messageKey, "Invalid username/password") + "</div>";
    }

    /**
     * When a login attempt failed, both credential fields are flagged invalid and pointed at the
     * {@code #login-error} message (same pattern as the settings screen's {@code slp-fqdn} field)
     * so assistive technology announces the association, not just the visual error block.
     */
    private static String fieldErrorAttrs(String loginError) {
        if (loginError == null) {
            return "";
        }
        return " aria-invalid=\"true\" aria-describedby=\"login-error\"";
    }

    private static String siteField(String site) {
        if (!WebUtils.isValidSiteKey(site)) {
            return "";
        }
        return hiddenField("site", site);
    }

    /**
     * The valve resolves the site from the host, which fails for a host no site declares: such a host
     * is governed by systemsite (see {@link LoginSite}), so the Google form names it explicitly.
     */
    private String googleSiteField(HttpServletRequest request) {
        String serverName = request.getServerName();
        if (siteResolver == null || serverName == null) {
            return "";
        }
        try {
            LoginSite site = LoginSite.of(siteResolver, serverName.toLowerCase(Locale.ROOT));
            return site != null && site.isFallback() ? hiddenField("siteKey", site.siteKey()) : "";
        } catch (RuntimeException e) {
            LOGGER.warn("Could not resolve the login site of host {}; letting the valve resolve it", serverName, e);
            return "";
        }
    }

    /** The governing site's button text (see {@link LoginSite}); empty = the translated default. */
    private String customButtonLabel(HttpServletRequest request) {
        String serverName = request.getServerName();
        if (siteResolver == null || siteHostsStore == null || serverName == null) {
            return "";
        }
        try {
            LoginSite site = LoginSite.of(siteResolver, serverName.toLowerCase(Locale.ROOT));
            return site == null ? "" : ButtonLabel.normalize(siteHostsStore.buttonLabel(site.siteKey()));
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the sign-in button text for host {}; using the default", serverName, e);
            return "";
        }
    }

    private static String hiddenField(String name, String value) {
        if (value.isEmpty()) {
            return "";
        }
        return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + escapeHtml(value) + "\"/>";
    }

    /** Localized label from Jahia's internal bundle, with a safe fallback for non-container contexts. */
    private static String label(Locale locale, String key, String fallback) {
        try {
            return escapeHtml(ResourceBundles.get("JahiaInternalResources", locale).getString(key));
        } catch (RuntimeException e) {
            // A missing key, a missing bundle, or a non-container unit-test run all fall back to
            // the default label — a label must never break the login page — but record why.
            LOGGER.debug("Falling back to default label for key '{}'", key, e);
            return escapeHtml(fallback);
        }
    }

    private static String escapeHtml(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '&': out.append("&amp;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
