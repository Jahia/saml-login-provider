package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.community.modules.samlloginprovider.SiteHostsStore;
import org.jahia.community.modules.samlloginprovider.SiteResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginProviderQueryTest {

    private static final String SITE_KEY = "digitall";

    @Mock
    private SiteHostsStore store;

    @Mock
    private SiteResolver resolver;

    private final SamlLoginProviderQuery query = new SamlLoginProviderQuery();

    @Test
    void config_permissionDenied_propagatesExceptionAndNeverTouchesTheServices() {
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            permissions.when(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY))
                    .thenThrow(new SecurityException("Access denied"));

            // Act / Assert
            assertThatThrownBy(() -> query.getConfig(SITE_KEY))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied");
            services.verifyNoInteractions();
        }
    }

    @Test
    void config_returnsTheSiteHostsAndItsServerNamesSorted() {
        // Arrange
        when(store.hosts(SITE_KEY)).thenReturn(List.of("digitall.example.com"));
        when(resolver.serverNamesOf(SITE_KEY)).thenReturn(Set.of("www.digitall.example.com", "digitall.example.com"));
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            services.when(SamlLoginProviderServices::siteHostsStore).thenReturn(store);
            services.when(SamlLoginProviderServices::siteResolver).thenReturn(resolver);

            // Act
            GqlSamlLoginProviderConfig result = query.getConfig(SITE_KEY);

            // Assert
            permissions.verify(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY));
            assertThat(result.getSiteKey()).isEqualTo(SITE_KEY);
            assertThat(result.getFqdn()).isEqualTo("digitall.example.com");
            assertThat(result.getServerNames()).containsExactly("digitall.example.com", "www.digitall.example.com");
        }
    }

    @Test
    void config_unconfiguredSite_returnsAnEmptyHostList() {
        // Arrange
        when(store.hosts(SITE_KEY)).thenReturn(List.of());
        when(resolver.serverNamesOf(SITE_KEY)).thenReturn(Set.of("digitall.example.com"));
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            services.when(SamlLoginProviderServices::siteHostsStore).thenReturn(store);
            services.when(SamlLoginProviderServices::siteResolver).thenReturn(resolver);

            // Act / Assert
            assertThat(query.getConfig(SITE_KEY).getFqdn()).isEmpty();
        }
    }
}
