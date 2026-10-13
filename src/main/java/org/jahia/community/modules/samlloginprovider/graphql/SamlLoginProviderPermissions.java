package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.community.modules.samlloginprovider.WebUtils;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;

import javax.jcr.RepositoryException;

/**
 * Site-admin permission gate shared by the query and mutation.
 *
 * <p>The configuration is per site ({@code SiteHostsStore}) and only ever applies to requests
 * resolving to that site ({@code SamlLoginUrlProvider}), so a per-site permission checked on that
 * site's node is the right scope: holding it on one site gives no power over any other. (A previous
 * version gated a single server-wide list with this kind of per-site check, which let any site's
 * admin rewrite login behavior for the whole platform; storage being per site is what makes the
 * per-site check sound now.) The permission is declared under {@code site-admin} -- see
 * {@code permissions.xml}.
 *
 * <p>The check runs on the caller's session (not a system session). A site that does not exist or
 * that the caller cannot read gets the same answer as a missing permission, so the API cannot be
 * used to enumerate sites. Failures are unchecked exceptions, so GraphQL reports a real error
 * rather than an ambiguous {@code null}: a denied request must be distinguishable from an empty
 * configuration.
 */
final class SamlLoginProviderPermissions {

    static final String PERMISSION = "siteAdminSamlLoginProvider";

    private SamlLoginProviderPermissions() {
    }

    static void checkSiteAdmin(String siteKey) {
        // Validated before it is used in a JCR path (and, downstream, in a file name).
        if (!WebUtils.isValidSiteKey(siteKey)) {
            throw new IllegalArgumentException("Invalid site key");
        }
        String sitePath = "/sites/" + siteKey;
        try {
            JCRSessionWrapper callerSession = JCRSessionFactory.getInstance().getCurrentUserSession();
            if (!callerSession.nodeExists(sitePath) || !callerSession.getNode(sitePath).hasPermission(PERMISSION)) {
                throw new SecurityException("Access denied: " + PERMISSION);
            }
        } catch (RepositoryException e) {
            throw new IllegalStateException("Site-level permission check failed", e);
        }
    }
}
