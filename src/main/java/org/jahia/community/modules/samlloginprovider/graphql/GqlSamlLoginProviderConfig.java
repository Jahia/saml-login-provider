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
    private final boolean samlConfigured;
    private final String buttonLabel;

    public GqlSamlLoginProviderConfig(String siteKey, String fqdn, List<String> serverNames, boolean samlConfigured,
                                      String buttonLabel) {
        this.siteKey = siteKey;
        this.fqdn = fqdn;
        this.serverNames = List.copyOf(serverNames);
        this.samlConfigured = samlConfigured;
        this.buttonLabel = buttonLabel;
    }

    @GraphQLField
    @GraphQLName("siteKey")
    @GraphQLDescription("The site this configuration belongs to")
    public String getSiteKey() {
        return siteKey;
    }

    @GraphQLField
    @GraphQLName("fqdn")
    @GraphQLDescription("Comma-separated hosts of this site sent straight to SAML login; empty = none (the site's "
            + "other hosts get the login page offering SAML)")
    public String getFqdn() {
        return fqdn;
    }

    @GraphQLField
    @GraphQLName("serverNames")
    @GraphQLDescription("The hosts this site answers on (server name and aliases): the only values fqdn may contain")
    public List<String> getServerNames() {
        return serverNames;
    }

    @GraphQLField
    @GraphQLName("samlConfigured")
    @GraphQLDescription("Whether the site has an enabled SAML connector. Without one the site keeps the standard login, "
            + "whatever its host list says")
    public boolean isSamlConfigured() {
        return samlConfigured;
    }

    @GraphQLField
    @GraphQLName("buttonLabel")
    @GraphQLDescription("Text of the login page's SAML sign-in button; empty = the default (translated) label")
    public String getButtonLabel() {
        return buttonLabel;
    }
}
