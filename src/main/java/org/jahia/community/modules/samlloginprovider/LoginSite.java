package org.jahia.community.modules.samlloginprovider;

/**
 * The site whose settings govern the login of a host: the ONE site declaring it, or
 * {@value #SYSTEMSITE} for a host that no site declares (typically the editing host, e.g.
 * edit.example.com, which Jahia serves without it being any site's server name).
 *
 * <p>A host declared by several sites is governed by none: Jahia serves it from an arbitrary one of
 * them, so no single site's settings -- not even systemsite's -- may decide how it logs in.
 *
 * <p>A fallback host must name its site to the SAML valve explicitly ({@code siteKey} parameter):
 * the valve otherwise resolves the site from the host, finds none, and fails with a 400.
 */
public final class LoginSite {

    /** Jahia's system site, which governs hosts no site declares. */
    public static final String SYSTEMSITE = "systemsite";

    private final String siteKey;
    private final boolean fallback;

    private LoginSite(String siteKey, boolean fallback) {
        this.siteKey = siteKey;
        this.fallback = fallback;
    }

    /** @param host the lower-cased request host; {@code null} yields {@code null} */
    public static LoginSite of(SiteResolver siteResolver, String host) {
        if (host == null) {
            return null;
        }
        String own = siteResolver.siteKeyFor(host);
        if (own != null) {
            return new LoginSite(own, false);
        }
        return siteResolver.isUnclaimed(host) ? new LoginSite(SYSTEMSITE, true) : null;
    }

    public String siteKey() {
        return siteKey;
    }

    /** True when no site declares the host, so systemsite governs it and must be named to the valve. */
    public boolean isFallback() {
        return fallback;
    }
}
