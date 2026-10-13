package org.jahia.community.modules.samlloginprovider;

import java.util.Set;

/** Which site a host belongs to, and which hosts a site answers on. */
public interface SiteResolver {

    /**
     * The key of the ONE site answering on this server name (its server name or an alias), or
     * {@code null} when no site does -- or when several do. Jahia does not keep server names unique
     * across sites, and its own lookup then returns an arbitrary one of them; letting any single
     * site's list govern such a host would let that site's admin redirect other sites' visitors.
     */
    String siteKeyFor(String serverName);

    /** Every site declaring this server name; empty when none does. Always read fresh. */
    Set<String> sitesClaiming(String serverName);

    /** The site's server name and aliases, lower-cased; empty for an unknown site. Always read fresh. */
    Set<String> serverNamesOf(String siteKey);
}
