package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.community.modules.samlloginprovider.ConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginProviderQueryExtensionTest {

    private static final String SITE_KEY = "mySite";

    @Mock
    private ConfigStore configStore;

    @Test
    void getSamlLoginProviderConfig_permissionDenied_propagatesExceptionAndNeverTouchesConfigStore() {
        // Arrange / Act / Assert
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            permissions.when(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY))
                    .thenThrow(new SecurityException("Access denied"));

            assertThatThrownBy(() -> SamlLoginProviderQueryExtension.getSamlLoginProviderConfig(SITE_KEY))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied");

            configStoreStatic.verifyNoInteractions();
        }
    }

    @Test
    void getSamlLoginProviderConfig_ioExceptionFromStore_surfacesAsIllegalStateExceptionWithGenericMessage() throws IOException {
        // Arrange
        when(configStore.read()).thenThrow(new IOException("disk failure detail"));

        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            configStoreStatic.when(ConfigStore::fromOsgi).thenReturn(configStore);

            // Act / Assert
            assertThatThrownBy(() -> SamlLoginProviderQueryExtension.getSamlLoginProviderConfig(SITE_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unable to read the SAML login configuration")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("disk failure detail"));

            permissions.verify(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY));
        }
    }

    @Test
    void getSamlLoginProviderConfig_validRead_returnsWrappedConfig() throws IOException {
        // Arrange
        when(configStore.read()).thenReturn("example.com,foo.example.com");

        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            configStoreStatic.when(ConfigStore::fromOsgi).thenReturn(configStore);

            // Act
            GqlSamlLoginProviderConfig result = SamlLoginProviderQueryExtension.getSamlLoginProviderConfig(SITE_KEY);

            // Assert
            assertThat(result.getFqdn()).isEqualTo("example.com,foo.example.com");
            permissions.verify(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY));
        }
    }
}
