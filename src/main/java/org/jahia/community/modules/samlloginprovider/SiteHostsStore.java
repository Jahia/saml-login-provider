package org.jahia.community.modules.samlloginprovider;

import java.io.IOException;
import java.util.List;

/**
 * Per-site configuration: for each site, the hosts whose unauthenticated visitors are sent
 * straight to the SAML connect endpoint. A site without hosts is not configured, and the module
 * then leaves its login flow alone.
 */
public interface SiteHostsStore {

    /** The site's canonical (lower-cased) hosts; empty when the site is not configured. */
    List<String> hosts(String siteKey);

    /**
     * Replaces the site's hosts. An empty list removes the site's configuration.
     *
     * @throws IllegalArgumentException for an unsafe site key or an invalid host
     */
    void save(String siteKey, List<String> hosts) throws IOException;
}
