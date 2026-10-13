package org.jahia.community.modules.samlloginprovider;

import org.jahia.modules.jahiaauth.service.ConnectorConfig;
import org.jahia.modules.jahiaauth.service.SettingsService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SamlSetup} read from jahia-authentication's {@link SettingsService} -- the same store
 * saml-authentication-valve resolves a site's connector from, whether the connector was written as
 * a {@code karaf/etc} factory configuration or from the site's Authentication settings screen.
 */
@Component(service = SamlSetup.class, immediate = true)
public class JahiaAuthSamlSetup implements SamlSetup {

    /**
     * Connector name and enabling property of saml-authentication-valve ("Saml.enabled" in a
     * connector .cfg). The valve keeps them in an internal, unexported package
     * ({@code org.jahia.modules.saml2.internal.SAML2Util} / {@code SAML2Constants}).
     */
    static final String SAML_CONNECTOR_NAME = "Saml";
    static final String ENABLED_PROPERTY = "enabled";

    private static final Logger LOGGER = LoggerFactory.getLogger(JahiaAuthSamlSetup.class);

    private final SettingsService settingsService;

    @Activate
    public JahiaAuthSamlSetup(@Reference SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public boolean isConfigured(String siteKey) {
        if (siteKey == null) {
            return false;
        }
        try {
            ConnectorConfig connector = settingsService.getConnectorConfig(siteKey, SAML_CONNECTOR_NAME);
            return connector != null && Boolean.TRUE.equals(connector.getBooleanProperty(ENABLED_PROPERTY));
        } catch (RuntimeException e) {
            // jahia-authentication can fail for a site with no settings at all: that is "not set up"
            LOGGER.debug("Could not read the SAML connector of site {}", siteKey, e);
            return false;
        }
    }
}
