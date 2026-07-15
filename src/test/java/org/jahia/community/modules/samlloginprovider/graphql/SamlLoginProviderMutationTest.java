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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SamlLoginProviderMutationTest {

    private static final String SITE_KEY = "mySite";

    @Mock
    private ConfigStore configStore;

    private final SamlLoginProviderMutation mutation = new SamlLoginProviderMutation();

    @Test
    void update_permissionDenied_propagatesExceptionAndNeverTouchesConfigStore() {
        // Arrange / Act / Assert
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            permissions.when(SamlLoginProviderPermissions::checkServerAdmin)
                    .thenThrow(new SecurityException("Access denied"));

            assertThatThrownBy(() -> mutation.update(SITE_KEY, "example.com"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied");

            configStoreStatic.verifyNoInteractions();
        }
    }

    @Test
    void update_invalidHostList_throwsIllegalArgumentExceptionAndNeverCallsStore() {
        // Arrange / Act / Assert: "-bad.com" has a leading hyphen -> rejected by HostList.isValid
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {

            assertThatThrownBy(() -> mutation.update(SITE_KEY, "example.com,-bad.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid host list");

            configStoreStatic.verifyNoInteractions();
            permissions.verify(SamlLoginProviderPermissions::checkServerAdmin);
        }
    }

    @Test
    void update_ioExceptionFromStore_surfacesAsIllegalStateExceptionWithGenericMessage() throws IOException {
        // Arrange
        doThrow(new IOException("disk failure detail")).when(configStore).store(anyString());

        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            configStoreStatic.when(ConfigStore::fromOsgi).thenReturn(configStore);

            // Act / Assert
            assertThatThrownBy(() -> mutation.update(SITE_KEY, "example.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unable to store the SAML login configuration")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("disk failure detail"));
        }
    }

    @Test
    void update_validUpdate_storesCanonicalFormAndReturnsWrappedConfig() throws IOException {
        // Arrange
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            configStoreStatic.when(ConfigStore::fromOsgi).thenReturn(configStore);

            // Act
            GqlSamlLoginProviderConfig result = mutation.update(SITE_KEY, " Example.COM , Foo.BAR ");

            // Assert
            verify(configStore).store("example.com,foo.bar");
            assertThat(result.getFqdn()).isEqualTo("example.com,foo.bar");
            permissions.verify(SamlLoginProviderPermissions::checkServerAdmin);
        }
    }

    @Test
    void update_nullFqdn_storesEmptyCanonicalForm() throws IOException {
        // Arrange
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            configStoreStatic.when(ConfigStore::fromOsgi).thenReturn(configStore);

            // Act
            GqlSamlLoginProviderConfig result = mutation.update(SITE_KEY, null);

            // Assert
            verify(configStore).store("");
            assertThat(result.getFqdn()).isEmpty();
        }
    }

    @Test
    void update_nullSiteKey_stillAuthorizesAndStoresConfig() throws IOException {
        // Arrange: siteKey is caller-context only, so a null value must not change behavior
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<ConfigStore> configStoreStatic = mockStatic(ConfigStore.class)) {
            configStoreStatic.when(ConfigStore::fromOsgi).thenReturn(configStore);

            // Act
            GqlSamlLoginProviderConfig result = mutation.update(null, "example.com");

            // Assert
            verify(configStore).store("example.com");
            assertThat(result.getFqdn()).isEqualTo("example.com");
            permissions.verify(SamlLoginProviderPermissions::checkServerAdmin);
        }
    }
}
