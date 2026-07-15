package org.jahia.community.modules.samlloginprovider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Small shared helpers used across the module so the security-sensitive bits
 * (URL encoding of the {@code redirect} parameter and site-key validation)
 * have a single authoritative implementation rather than being duplicated
 * between {@link SamlLoginUrlProvider}, {@link SamlLoginPageFilter} and the
 * GraphQL permission gate.
 */
public final class WebUtils {

    /**
     * A plain Jahia site key. Must start with an alphanumeric character so a
     * value can never collide with the {@code __PLACEHOLDER__} tokens used by
     * {@link SamlLoginPageFilter} (those all start with an underscore).
     */
    private static final Pattern SITE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]*$");

    private WebUtils() {
    }

    /** True only for a plain, separator-free, markup-free site key. */
    public static boolean isValidSiteKey(String siteKey) {
        return siteKey != null && SITE_KEY_PATTERN.matcher(siteKey).matches();
    }

    /** UTF-8 {@code application/x-www-form-urlencoded} encoding for query values. */
    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
