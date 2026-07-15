package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.community.modules.samlloginprovider.WebUtils;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;

import javax.jcr.RepositoryException;

/**
 * Site-admin permission gate shared by the query and mutation extensions.
 *
 * <p>The check runs on the caller's session (not a system session) so site
 * admin scoping is enforced — same pattern as the securitytxt module. The
 * {@code siteKey} is validated <em>before</em> it is concatenated into a JCR
 * path so it can never normalize to a different node (e.g. {@code ..} → root).
 *
 * <p>Failures are raised as unchecked exceptions so the GraphQL layer reports a
 * real error to the caller instead of an ambiguous {@code null}: a denied or
 * malformed request must be distinguishable from an empty configuration.
 */
final class SamlLoginProviderPermissions {

    static final String PERMISSION = "siteAdminSamlLoginProvider";

    private SamlLoginProviderPermissions() {
    }

    static void checkSiteAdmin(String siteKey) {
        if (!WebUtils.isValidSiteKey(siteKey)) {
            throw new SecurityException("Invalid site key");
        }
        String sitePath = "/sites/" + siteKey;
        try {
            JCRSessionWrapper callerSession = JCRSessionFactory.getInstance().getCurrentUserSession();
            if (!callerSession.nodeExists(sitePath) || !callerSession.getNode(sitePath).hasPermission(PERMISSION)) {
                throw new SecurityException("Access denied: " + PERMISSION);
            }
        } catch (RepositoryException e) {
            throw new IllegalStateException("Permission check failed for site " + siteKey, e);
        }
    }
}
