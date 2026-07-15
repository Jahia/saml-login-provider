package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.modules.samlloginprovider.ConfigStore;
import org.jahia.community.modules.samlloginprovider.HostList;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLName("SamlLoginProviderMutations")
@GraphQLDescription("Automatic SAML login management mutations")
public class SamlLoginProviderMutationExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamlLoginProviderMutationExtension.class);

    private SamlLoginProviderMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("updateSamlLoginProviderConfig")
    @GraphQLDescription("Update the automatic SAML login configuration (server-wide). Empty fqdn disables the redirect.")
    public static GqlSamlLoginProviderConfig updateSamlLoginProviderConfig(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey,
            @GraphQLName("fqdn") final String fqdn) {
        SamlLoginProviderPermissions.checkSiteAdmin(siteKey);

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
