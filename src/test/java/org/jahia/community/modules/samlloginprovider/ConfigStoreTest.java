package org.jahia.community.modules.samlloginprovider;

import org.jahia.osgi.BundleUtils;
import org.jahia.services.modulemanager.spi.Config;
import org.jahia.services.modulemanager.spi.ConfigService;
import org.jahia.services.modulemanager.util.PropertiesValues;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfigStoreTest {

    @Mock
    private ConfigService configService;

    @Mock
    private Config config;

    @Mock
    private PropertiesValues propertiesValues;

    @Test
    void read_missingProperty_returnsEmptyString() throws IOException {
        // Arrange
        when(configService.getConfig(SamlLoginUrlProvider.CONFIG_PID)).thenReturn(config);
        when(config.getValues()).thenReturn(propertiesValues);
        when(propertiesValues.getProperty(SamlLoginUrlProvider.FQDN_PROPERTY)).thenReturn(null);
        ConfigStore configStore = new ConfigStore(configService);

        // Act
        String result = configStore.read();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void read_existingProperty_returnsStoredValue() throws IOException {
        // Arrange
        when(configService.getConfig(SamlLoginUrlProvider.CONFIG_PID)).thenReturn(config);
        when(config.getValues()).thenReturn(propertiesValues);
        when(propertiesValues.getProperty(SamlLoginUrlProvider.FQDN_PROPERTY)).thenReturn("example.com,foo.example.com");
        ConfigStore configStore = new ConfigStore(configService);

        // Act
        String result = configStore.read();

        // Assert
        assertThat(result).isEqualTo("example.com,foo.example.com");
    }

    @Test
    void store_emptyCanonical_removesProperty() throws IOException {
        // Arrange
        when(configService.getConfig(SamlLoginUrlProvider.CONFIG_PID)).thenReturn(config);
        when(config.getValues()).thenReturn(propertiesValues);
        ConfigStore configStore = new ConfigStore(configService);

        // Act
        configStore.store("");

        // Assert
        verify(propertiesValues).removeProperty(SamlLoginUrlProvider.FQDN_PROPERTY);
        verify(propertiesValues, never()).setProperty(eq(SamlLoginUrlProvider.FQDN_PROPERTY), anyString());
        verify(configService).storeConfig(config);
    }

    @Test
    void store_nonEmptyCanonical_setsProperty() throws IOException {
        // Arrange
        when(configService.getConfig(SamlLoginUrlProvider.CONFIG_PID)).thenReturn(config);
        when(config.getValues()).thenReturn(propertiesValues);
        ConfigStore configStore = new ConfigStore(configService);

        // Act
        configStore.store("example.com,foo.example.com");

        // Assert
        verify(propertiesValues).setProperty(SamlLoginUrlProvider.FQDN_PROPERTY, "example.com,foo.example.com");
        verify(propertiesValues, never()).removeProperty(anyString());
        verify(configService).storeConfig(config);
    }

    @Test
    void fromOsgi_serviceUnavailable_throwsIllegalStateException() {
        // Arrange / Act / Assert
        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(eq(ConfigService.class), isNull()))
                    .thenReturn(null);

            assertThatThrownBy(ConfigStore::fromOsgi)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ConfigService is unavailable");
        }
    }

    @Test
    void fromOsgi_serviceAvailable_wrapsResolvedService() throws IOException {
        // Arrange
        when(configService.getConfig(SamlLoginUrlProvider.CONFIG_PID)).thenReturn(config);
        when(config.getValues()).thenReturn(propertiesValues);
        when(propertiesValues.getProperty(SamlLoginUrlProvider.FQDN_PROPERTY)).thenReturn("resolved.example.com");

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(eq(ConfigService.class), isNull()))
                    .thenReturn(configService);

            // Act
            ConfigStore configStore = ConfigStore.fromOsgi();

            // Assert: the returned store is backed by the exact service resolved from OSGi
            assertThat(configStore.read()).isEqualTo("resolved.example.com");
        }
    }
}
