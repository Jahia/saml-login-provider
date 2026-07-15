package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

/**
 * Publishes a single, namespaced {@code samlLoginProvider} field on the root
 * GraphQL {@code Mutation} type (see {@code DXGraphQLProvider.Mutation}), with
 * the actual operations nested as fields of {@link SamlLoginProviderMutation}.
 * See {@link SamlLoginProviderQueryExtension} for why the operations are
 * grouped under one namespace container rather than added as flat root
 * fields.
 */
@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLDescription("Automatic SAML login management mutations")
public class SamlLoginProviderMutationExtension {

    private SamlLoginProviderMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("samlLoginProvider")
    @GraphQLDescription("SAML auto-login mutation namespace")
    public static SamlLoginProviderMutation samlLoginProvider() {
        return new SamlLoginProviderMutation();
    }
}
