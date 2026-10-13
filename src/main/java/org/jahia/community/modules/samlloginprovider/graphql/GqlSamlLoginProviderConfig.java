package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;

import java.util.List;

@GraphQLName("SamlLoginProviderConfig")
@GraphQLDescription("A site's automatic SAML login configuration")
public class GqlSamlLoginProviderConfig {

    private final String siteKey;
    private final String fqdn;
    private final List<String> serverNames;

    public GqlSamlLoginProviderConfig(String siteKey, String fqdn, List<String> serverNames) {
        this.siteKey = siteKey;
        this.fqdn = fqdn;
        this.serverNames = List.copyOf(serverNames);
    }

    @GraphQLField
    @GraphQLName("siteKey")
    @GraphQLDescription("The site this configuration belongs to")
    public String getSiteKey() {
        return siteKey;
    }

    @GraphQLField
    @GraphQLName("fqdn")
    @GraphQLDescription("Comma-separated hosts of this site sent straight to SAML login; empty = not configured")
    public String getFqdn() {
        return fqdn;
    }

    @GraphQLField
    @GraphQLName("serverNames")
    @GraphQLDescription("The hosts this site answers on (server name and aliases): the only values fqdn may contain")
    public List<String> getServerNames() {
        return serverNames;
    }
}
