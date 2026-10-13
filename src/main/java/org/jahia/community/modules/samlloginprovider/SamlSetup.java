package org.jahia.community.modules.samlloginprovider;

/** Whether a site has SAML authentication set up, i.e. a working target for {@code /home.connect.saml}. */
@FunctionalInterface
public interface SamlSetup {

    /** True when the site has an enabled SAML connector. */
    boolean isConfigured(String siteKey);
}
