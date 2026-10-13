package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.community.modules.samlloginprovider.HostList;
import org.jahia.community.modules.samlloginprovider.SiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
    @GraphQLDescription("Update a site's automatic SAML login configuration. Empty fqdn turns it off for that site.")
    public GqlSamlLoginProviderConfig update(
            @GraphQLName("siteKey") @GraphQLNonNull
            @GraphQLDescription("The site to update; requires siteAdminSamlLoginProvider on that site")
            final String siteKey,
            @GraphQLName("fqdn")
            @GraphQLDescription("Comma-separated hosts, each the site's server name or one of its aliases")
            final String fqdn) {
        SamlLoginProviderPermissions.checkSiteAdmin(siteKey);

        // Do NOT echo the submitted value anywhere: it is untrusted input.
        List<String> hosts = HostList.parse(fqdn == null ? "" : fqdn);
        if (!HostList.isValid(hosts)) {
            LOGGER.warn("Rejected an invalid host list submitted for site {}", siteKey);
            throw new IllegalArgumentException("Invalid host list: enter comma-separated RFC 1123 hostnames, or leave it empty.");
        }

        // Only the site's own names: a host resolving to another site would never be routed here,
        // so accepting it would just store a setting that silently does nothing.
        SiteResolver resolver = SamlLoginProviderServices.siteResolver();
        Set<String> serverNames = resolver.serverNamesOf(siteKey);
        if (!serverNames.containsAll(hosts)) {
            LOGGER.warn("Rejected hosts not belonging to site {}", siteKey);
            throw new IllegalArgumentException("Each host must be this site's server name or one of its aliases: "
                    + String.join(", ", sorted(serverNames)) + ".");
        }
        // ...and declared by this site ALONE. Jahia does not keep server names unique, and routes a
        // shared one to an arbitrary site: no single site's list may govern it (routing ignores it
        // too). The host is one of the caller's own site names; the other site is not named.
        for (String host : hosts) {
            if (!resolver.sitesClaiming(host).equals(Set.of(siteKey))) {
                LOGGER.warn("Rejected host {} for site {}: also declared by another site", host, siteKey);
                throw new IllegalArgumentException("This host is also declared by another site, so it cannot be "
                        + "listed: " + host + ". Give it to one site only.");
            }
        }

        try {
            SamlLoginProviderServices.siteHostsStore().save(siteKey, hosts);
        } catch (IOException e) {
            // log the detail server-side, surface a generic GraphQL error to the caller
            LOGGER.error("Error storing the saml-login-provider configuration of site {}", siteKey, e);
            throw new IllegalStateException("Unable to store the SAML login configuration");
        }
        return new GqlSamlLoginProviderConfig(siteKey, HostList.canonical(hosts), sorted(serverNames));
    }

    static List<String> sorted(Collection<String> values) {
        return values.stream().sorted().collect(Collectors.toList());
    }
}
