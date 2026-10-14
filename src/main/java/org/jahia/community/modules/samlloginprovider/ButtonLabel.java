package org.jahia.community.modules.samlloginprovider;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * A site's custom text for the choice page's SAML sign-in button ("Sign in with Google" by default).
 * Empty means the default, translated label.
 *
 * <p>Plain text: rendered HTML-escaped, so any character is safe on the page. Control, format
 * (zero-width, bidi overrides: a blank or spoofed button) and line/paragraph separator characters
 * are refused -- the Unicode categories Cc, Cf, Zl and Zp, which the settings screen checks too. Stored URL-encoded in the site's {@code .cfg}: Felix FileInstall
 * reads values there as typed properties (a leading {@code [} is an array, {@code "} starts a quoted
 * token, {@code ${...}} is interpolated), and a free-text value could otherwise be misread -- or
 * break the whole file, host list included. A hand-written plain value still reads as itself (with
 * {@code +} read as a space).
 */
public final class ButtonLabel {

    /** In UTF-16 units, as the settings screen counts them. */
    public static final int MAX_LENGTH = 80;

    private static final char BYTE_ORDER_MARK = '\uFEFF';

    private ButtonLabel() {
    }

    /**
     * Trimmed of what the settings screen's JavaScript {@code trim()} removes (whitespace, space
     * separators such as the no-break space, and U+FEFF); {@code null} is the default (empty).
     */
    public static String normalize(String label) {
        if (label == null) {
            return "";
        }
        int start = 0;
        int end = label.length();
        while (start < end && isTrimmed(label.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(label.charAt(end - 1))) {
            end--;
        }
        return label.substring(start, end);
    }

    public static boolean isValid(String label) {
        return label != null && label.length() <= MAX_LENGTH
                && label.codePoints().noneMatch(ButtonLabel::isForbidden);
    }

    /** For the {@code .cfg}: only {@code [A-Za-z0-9%+._*-]}, which no .cfg reader interprets. */
    static String encode(String label) {
        return URLEncoder.encode(label, StandardCharsets.UTF_8);
    }

    /** @throws IllegalArgumentException for a broken {@code %} escape */
    static String decode(String stored) {
        return URLDecoder.decode(stored, StandardCharsets.UTF_8);
    }

    private static boolean isTrimmed(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == BYTE_ORDER_MARK;
    }

    private static boolean isForbidden(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
