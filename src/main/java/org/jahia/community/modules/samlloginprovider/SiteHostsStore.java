package org.jahia.community.modules.samlloginprovider;

import java.io.IOException;
import java.util.List;

/**
 * Per-site configuration: for each site, the hosts whose unauthenticated visitors are sent
 * straight to the SAML connect endpoint, and the text of the choice page's SAML sign-in button.
 */
public interface SiteHostsStore {

    /** The site's canonical (lower-cased) hosts; empty when the site is not configured. */
    List<String> hosts(String siteKey);

    /** The site's custom sign-in button text (see {@link ButtonLabel}); empty = the default label. */
    default String buttonLabel(String siteKey) {
        return "";
    }

    /**
     * Replaces the site's configuration. No hosts and an empty label remove it.
     *
     * @param buttonLabel normalized with {@link ButtonLabel#normalize}; empty = the default label;
     *                    {@code null} keeps the current one (resolved within the same write)
     * @throws IllegalArgumentException for an unsafe site key, an invalid host or an invalid label
     */
    void save(String siteKey, List<String> hosts, String buttonLabel) throws IOException;
}
