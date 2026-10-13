package org.jahia.community.modules.samlloginprovider;

import org.jahia.modules.jahiaauth.service.ConnectorConfig;
import org.jahia.modules.jahiaauth.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JahiaAuthSamlSetupTest {

    private static final String SITE_KEY = "digitall";

    @Mock
    private SettingsService settingsService;

    @Mock
    private ConnectorConfig connector;

    @Test
    void isConfigured_enabledSamlConnector_isTrue() {
        when(settingsService.getConnectorConfig(SITE_KEY, "Saml")).thenReturn(connector);
        when(connector.getBooleanProperty("enabled")).thenReturn(true);

        assertThat(new JahiaAuthSamlSetup(settingsService).isConfigured(SITE_KEY)).isTrue();
    }

    @Test
    void isConfigured_disabledSamlConnector_isFalse() {
        when(settingsService.getConnectorConfig(SITE_KEY, "Saml")).thenReturn(connector);
        when(connector.getBooleanProperty("enabled")).thenReturn(false);

        assertThat(new JahiaAuthSamlSetup(settingsService).isConfigured(SITE_KEY)).isFalse();
    }

    @Test
    void isConfigured_enabledFlagMissing_isFalse() {
        when(settingsService.getConnectorConfig(SITE_KEY, "Saml")).thenReturn(connector);
        when(connector.getBooleanProperty("enabled")).thenReturn(null);

        assertThat(new JahiaAuthSamlSetup(settingsService).isConfigured(SITE_KEY)).isFalse();
    }

    @Test
    void isConfigured_noSamlConnectorForTheSite_isFalse() {
        when(settingsService.getConnectorConfig(SITE_KEY, "Saml")).thenReturn(null);

        assertThat(new JahiaAuthSamlSetup(settingsService).isConfigured(SITE_KEY)).isFalse();
    }

    @Test
    void isConfigured_settingsServiceFailure_isFalse() {
        // jahia-authentication may NPE for a site with no settings at all: never break the login
        when(settingsService.getConnectorConfig(SITE_KEY, "Saml")).thenThrow(new NullPointerException());

        assertThat(new JahiaAuthSamlSetup(settingsService).isConfigured(SITE_KEY)).isFalse();
    }

    @Test
    void isConfigured_nullSiteKey_isFalse() {
        assertThat(new JahiaAuthSamlSetup(settingsService).isConfigured(null)).isFalse();
    }
}
