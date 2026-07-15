package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.modules.samlloginprovider.ConfigStore;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLName("SamlLoginProviderQueries")
@GraphQLDescription("Automatic SAML login management queries")
public class SamlLoginProviderQueryExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamlLoginProviderQueryExtension.class);

    private SamlLoginProviderQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("samlLoginProviderConfig")
    @GraphQLDescription("Get the automatic SAML login configuration (server-wide)")
    public static GqlSamlLoginProviderConfig getSamlLoginProviderConfig(
            @GraphQLName("siteKey") @GraphQLNonNull final String siteKey) {
        SamlLoginProviderPermissions.checkSiteAdmin(siteKey);
        try {
            return new GqlSamlLoginProviderConfig(ConfigStore.fromOsgi().read());
        } catch (IOException e) {
            // log the detail server-side, surface a generic GraphQL error to the caller
            LOGGER.error("Error reading saml-login-provider configuration", e);
            throw new IllegalStateException("Unable to read the SAML login configuration");
        }
    }
}
