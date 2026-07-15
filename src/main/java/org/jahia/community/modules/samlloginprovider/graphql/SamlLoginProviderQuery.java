package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import org.jahia.community.modules.samlloginprovider.ConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Namespace container exposed as {@code Query.samlLoginProvider} — see
 * {@link SamlLoginProviderQueryExtension}.
 */
@GraphQLName("SamlLoginProviderQuery")
@GraphQLDescription("Automatic SAML login management queries")
public class SamlLoginProviderQuery {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamlLoginProviderQuery.class);

    @GraphQLField
    @GraphQLName("config")
    @GraphQLDescription("Get the automatic SAML login configuration (server-wide)")
    public GqlSamlLoginProviderConfig getConfig(
            @GraphQLName("siteKey")
            @GraphQLDescription("Caller context only (e.g. which admin site tab the request was made from). "
                    + "It does NOT scope storage and has no effect on authorization: this configuration is "
                    + "server-wide and access is gated by the genuine server-level samlLoginProviderAdmin permission.")
            final String siteKey) {
        SamlLoginProviderPermissions.checkServerAdmin();
        try {
            return new GqlSamlLoginProviderConfig(ConfigStore.fromOsgi().read());
        } catch (IOException e) {
            // log the detail server-side, surface a generic GraphQL error to the caller
            LOGGER.error("Error reading saml-login-provider configuration", e);
            throw new IllegalStateException("Unable to read the SAML login configuration");
        }
    }
}
