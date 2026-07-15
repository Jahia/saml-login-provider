package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.community.modules.samlloginprovider.ConfigStore;
import org.jahia.community.modules.samlloginprovider.HostList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Namespace container exposed as {@code Mutation.samlLoginProvider} — see
 * {@link SamlLoginProviderMutationExtension}.
 */
@GraphQLName("SamlLoginProviderMutation")
@GraphQLDescription("Automatic SAML login management mutations")
public class SamlLoginProviderMutation {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamlLoginProviderMutation.class);

    @GraphQLField
    @GraphQLName("update")
    @GraphQLDescription("Update the automatic SAML login configuration (server-wide). Empty fqdn disables the redirect.")
    public GqlSamlLoginProviderConfig update(
            @GraphQLName("siteKey")
            @GraphQLDescription("Caller context only (e.g. which admin site tab the request was made from). "
                    + "It does NOT scope storage and has no effect on authorization: this configuration is "
                    + "server-wide and access is gated by the genuine server-level samlLoginProviderAdmin permission.")
            final String siteKey,
            @GraphQLName("fqdn") final String fqdn) {
        SamlLoginProviderPermissions.checkServerAdmin();

        // Do NOT echo the submitted value anywhere: it is untrusted input.
        List<String> hosts = HostList.parse(fqdn == null ? "" : fqdn);
        if (!HostList.isValid(hosts)) {
            LOGGER.warn("Rejected invalid host list submitted for the saml-login-provider configuration");
            throw new IllegalArgumentException("Invalid host list: enter comma-separated RFC 1123 hostnames, or leave it empty.");
        }

        String canonical = HostList.canonical(hosts);
        try {
            ConfigStore.fromOsgi().store(canonical);
            return new GqlSamlLoginProviderConfig(canonical);
        } catch (IOException e) {
            // log the detail server-side, surface a generic GraphQL error to the caller
            LOGGER.error("Error storing saml-login-provider configuration", e);
            throw new IllegalStateException("Unable to store the SAML login configuration");
        }
    }
}
