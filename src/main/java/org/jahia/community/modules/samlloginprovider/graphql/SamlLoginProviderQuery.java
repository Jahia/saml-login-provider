package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.community.modules.samlloginprovider.ButtonLabel;
import org.jahia.community.modules.samlloginprovider.HostList;
import org.jahia.community.modules.samlloginprovider.SiteHostsStore;

import java.util.List;

/**
 * Namespace container exposed as {@code Query.samlLoginProvider}: see
 * {@link SamlLoginProviderQueryExtension}.
 */
@GraphQLName("SamlLoginProviderQuery")
@GraphQLDescription("Automatic SAML login management queries")
public class SamlLoginProviderQuery {

    @GraphQLField
    @GraphQLName("config")
    @GraphQLDescription("Get a site's automatic SAML login configuration")
    public GqlSamlLoginProviderConfig getConfig(
            @GraphQLName("siteKey") @GraphQLNonNull
            @GraphQLDescription("The site to read; requires siteAdminSamlLoginProvider on that site")
            final String siteKey) {
        SamlLoginProviderPermissions.checkSiteAdmin(siteKey);
        SiteHostsStore store = SamlLoginProviderServices.siteHostsStore();
        List<String> hosts = store.hosts(siteKey);
        return new GqlSamlLoginProviderConfig(siteKey, HostList.canonical(hosts),
                SamlLoginProviderMutation.sorted(SamlLoginProviderServices.siteResolver().serverNamesOf(siteKey)),
                SamlLoginProviderServices.samlSetup().isConfigured(siteKey),
                ButtonLabel.normalize(store.buttonLabel(siteKey)));
    }
}
