package org.jahia.community.modules.samlloginprovider;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parsing and validation of the comma-separated host list stored in the
 * {@code fqdn} property. Hosts are matched against
 * {@code request.getServerName()}, so:
 * <ul>
 * <li>single-label hosts ({@code localhost}) and IPv4-like literals are
 * accepted on purpose (local/test setups, IP-based access); no semantic
 * 0-255 range check is applied to numeric labels;</li>
 * <li>the absolute-FQDN trailing-dot notation ({@code host.example.}) is
 * rejected: {@code getServerName()} never returns it, so storing it would
 * silently never match.</li>
 * </ul>
 */
public final class HostList {

    /**
     * A single RFC 1123 label: 1-63 letters, digits or hyphens. A flat character
     * class with a bounded quantifier, deliberately no nested quantifiers, so
     * there is no catastrophic-backtracking / stack-overflow risk on hostile input.
     */
    private static final Pattern LABEL_PATTERN = Pattern.compile("[A-Za-z0-9-]{1,63}");
    private static final int MAX_HOSTNAME_LENGTH = 253;

    private HostList() {
    }

    /** Splits on commas (the only separator), trims and lower-cases each entry. */
    public static List<String> parse(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    /** An empty list is valid (= automatic redirect disabled). */
    public static boolean isValid(List<String> hosts) {
        return hosts.stream().allMatch(HostList::isValidHostname);
    }

    /** Canonical storage form: comma-joined, no spaces. */
    public static String canonical(List<String> hosts) {
        return String.join(",", hosts);
    }

    private static boolean isValidHostname(String fqdn) {
        if (fqdn.isEmpty() || fqdn.length() > MAX_HOSTNAME_LENGTH) {
            return false;
        }
        // Validate each dot-separated label on its own. The -1 limit keeps trailing
        // empty labels (e.g. "host." or "a..b") so they are rejected rather than dropped.
        for (String label : fqdn.split("\\.", -1)) {
            if (!LABEL_PATTERN.matcher(label).matches()
                    || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-') {
                return false;
            }
        }
        return true;
    }
}
