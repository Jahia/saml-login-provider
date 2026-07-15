package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;

import javax.jcr.RepositoryException;

/**
 * Server-admin permission gate shared by the query and mutation extensions.
 *
 * <p>The configuration this module exposes ({@code org.jahia.community.samlloginprovider},
 * the {@code fqdn} host list) is genuinely server-wide: it is stored via
 * {@code ConfigService}, not JCR, and is not scoped to any site (see
 * {@code ConfigStore} / {@code SamlLoginUrlProvider}). Gating it with a
 * per-site permission (e.g. checked on {@code /sites/{siteKey}}) would let an
 * admin who only holds site-admin rights on one low-value site rewrite SAML
 * auth behavior for the entire platform — a privilege escalation. The check
 * therefore runs on the JCR <em>root</em> node, mirroring how genuinely
 * server-wide Jahia permissions are checked elsewhere (e.g. {@code adminCache},
 * {@code adminTemplates}), and the permission itself is declared as a child of
 * the built-in {@code admin} ("server settings") permission node rather than
 * {@code site-admin} — see {@code permissions.xml}.
 *
 * <p>The check runs on the caller's session (not a system session), and
 * failures are raised as unchecked exceptions so the GraphQL layer reports a
 * real error to the caller instead of an ambiguous {@code null}: a denied
 * request must be distinguishable from an empty configuration.
 */
final class SamlLoginProviderPermissions {

    static final String PERMISSION = "samlLoginProviderAdmin";

    private SamlLoginProviderPermissions() {
    }

    static void checkServerAdmin() {
        try {
            JCRSessionWrapper callerSession = JCRSessionFactory.getInstance().getCurrentUserSession();
            if (!callerSession.getNode("/").hasPermission(PERMISSION)) {
                throw new SecurityException("Access denied: " + PERMISSION);
            }
        } catch (RepositoryException e) {
            throw new IllegalStateException("Server-level permission check failed", e);
        }
    }
}
