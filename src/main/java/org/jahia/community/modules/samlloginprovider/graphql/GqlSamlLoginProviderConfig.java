package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

@GraphQLName("SamlLoginProviderConfig")
@GraphQLDescription("Configuration of the automatic SAML login redirect")
public class GqlSamlLoginProviderConfig {

    private final String fqdn;

    public GqlSamlLoginProviderConfig(String fqdn) {
        this.fqdn = fqdn;
    }

    @GraphQLField
    @GraphQLName("fqdn")
    @GraphQLDescription("Comma-separated list of hosts the automatic SAML login applies to; empty = disabled")
    public String getFqdn() {
        return fqdn;
    }
}
