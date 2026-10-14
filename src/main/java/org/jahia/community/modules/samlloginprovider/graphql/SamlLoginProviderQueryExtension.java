package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

/**
 * Publishes a single, namespaced {@code samlLoginProvider} field on the root
 * GraphQL {@code Query} type (see {@code DXGraphQLProvider.Query}), with the
 * actual operations nested as fields of {@link SamlLoginProviderQuery}. Flat
 * root-level fields from different bundles can collide by name and break the
 * platform-wide GraphQL schema for every module; grouping under one
 * namespace container (mirroring the {@code securitytxt} module's
 * {@code SecurityTxtQueryExtension}) avoids that.
 */
@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLDescription("Automatic SAML login management queries")
public class SamlLoginProviderQueryExtension {

    private SamlLoginProviderQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("samlLoginProvider")
    @GraphQLDescription("SAML auto-login query namespace")
    public static SamlLoginProviderQuery samlLoginProvider() {
        return new SamlLoginProviderQuery();
    }
}
