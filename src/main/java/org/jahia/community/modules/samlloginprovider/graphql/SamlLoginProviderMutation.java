package org.jahia.community.modules.samlloginprovider.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.community.modules.samlloginprovider.HostList;
import org.jahia.community.modules.samlloginprovider.LoginSite;
import org.jahia.community.modules.samlloginprovider.SiteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    @GraphQLDescription("Update a site's automatic SAML login configuration. Empty fqdn: nobody is sent to SAML automatically.")
    public GqlSamlLoginProviderConfig update(
            @GraphQLName("siteKey") @GraphQLNonNull
            @GraphQLDescription("The site to update; requires siteAdminSamlLoginProvider on that site")
            final String siteKey,
            @GraphQLName("fqdn")
            @GraphQLDescription("Comma-separated hosts, each the site's server name or one of its aliases (systemsite: also hosts no site declares)")
            final String fqdn) {
        SamlLoginProviderPermissions.checkSiteAdmin(siteKey);

        // Do NOT echo the submitted value anywhere: it is untrusted input.
        List<String> hosts = HostList.parse(fqdn == null ? "" : fqdn);
        if (!HostList.isValid(hosts)) {
            LOGGER.warn("Rejected an invalid host list submitted for site {}", siteKey);
            throw new IllegalArgumentException("Invalid host list: enter comma-separated RFC 1123 hostnames, or leave it empty.");
        }

        SiteResolver resolver = SamlLoginProviderServices.siteResolver();
        Set<String> serverNames = resolver.serverNamesOf(siteKey);
        checkGovernedBy(resolver, siteKey, serverNames, hosts);

        try {
            SamlLoginProviderServices.siteHostsStore().save(siteKey, hosts);
        } catch (IOException e) {
            // log the detail server-side, surface a generic GraphQL error to the caller
            LOGGER.error("Error storing the saml-login-provider configuration of site {}", siteKey, e);
            throw new IllegalStateException("Unable to store the SAML login configuration");
        }
        return new GqlSamlLoginProviderConfig(siteKey, HostList.canonical(hosts), sorted(serverNames),
                SamlLoginProviderServices.samlSetup().isConfigured(siteKey));
    }

    /**
     * A site's list may only hold hosts whose login that site governs (see {@link LoginSite}), since
     * routing ignores any other: its own names, declared by it ALONE -- Jahia does not keep server
     * names unique and serves a shared one from an arbitrary site, so no single site's list may govern
     * it -- plus, for systemsite only, hosts that no site declares. Membership is checked for every
     * host before sharing, so a foreign host is reported as such. Messages never name another site.
     */
    private static void checkGovernedBy(SiteResolver resolver, String siteKey, Set<String> serverNames,
                                        List<String> hosts) {
        boolean systemsite = LoginSite.SYSTEMSITE.equals(siteKey);
        Map<String, Set<String>> claimants = new HashMap<>();
        for (String host : hosts) {
            if (serverNames.contains(host)) {
                continue;
            }
            Set<String> claiming = resolver.sitesClaiming(host);
            if (!systemsite || !claiming.isEmpty()) {
                LOGGER.warn("Rejected host {} for site {}: not one of its names", host, siteKey);
                String ownNames = String.join(", ", sorted(serverNames));
                throw new IllegalArgumentException(systemsite
                        ? "Each host must be declared by no site, or be this site's server name or one of its "
                        + "aliases: " + ownNames + "."
                        : "Each host must be this site's server name or one of its aliases: " + ownNames + ".");
            }
            claimants.put(host, claiming);
        }
        for (String host : hosts) {
            Set<String> claiming = claimants.computeIfAbsent(host, resolver::sitesClaiming);
            boolean governed = claiming.equals(Set.of(siteKey)) || (systemsite && claiming.isEmpty());
            if (!governed) {
                LOGGER.warn("Rejected host {} for site {}: also declared by another site", host, siteKey);
                throw new IllegalArgumentException("This host is also declared by another site, so it cannot be "
                        + "listed: " + host + ". Give it to one site only.");
            }
        }
    }

    static List<String> sorted(Collection<String> values) {
        return values.stream().sorted().collect(Collectors.toList());
    }
}
