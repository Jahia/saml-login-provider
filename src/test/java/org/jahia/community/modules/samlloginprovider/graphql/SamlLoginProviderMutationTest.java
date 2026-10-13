package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.community.modules.samlloginprovider.SamlSetup;
import org.jahia.community.modules.samlloginprovider.SiteHostsStore;
import org.jahia.community.modules.samlloginprovider.SiteResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginProviderMutationTest {

    private static final String SITE_KEY = "digitall";

    @Mock
    private SiteHostsStore store;

    @Mock
    private SiteResolver resolver;

    private final SamlLoginProviderMutation mutation = new SamlLoginProviderMutation();

    @Test
    void update_permissionDenied_propagatesExceptionAndNeverTouchesTheServices() {
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            permissions.when(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY))
                    .thenThrow(new SecurityException("Access denied"));

            // Act / Assert
            assertThatThrownBy(() -> mutation.update(SITE_KEY, "digitall.example.com"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied");
            services.verifyNoInteractions();
        }
    }

    @Test
    void update_invalidHostSyntax_isRejectedAndNothingIsStored() {
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            // Act / Assert: "-bad.com" has a leading hyphen -> rejected by HostList.isValid
            assertThatThrownBy(() -> mutation.update(SITE_KEY, "digitall.example.com,-bad.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid host list");
            permissions.verify(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY));
            services.verifyNoInteractions();
        }
    }

    @Test
    void update_hostNotBelongingToTheSite_isRejectedAndNothingIsStored() throws IOException {
        // Arrange: the site answers on two names; the submitted list adds someone else's host
        when(resolver.serverNamesOf(SITE_KEY)).thenReturn(Set.of("digitall.example.com", "www.digitall.example.com"));
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            services.when(SamlLoginProviderServices::siteResolver).thenReturn(resolver);

            // Act / Assert: the error lists the site's own names (trusted), never the submitted value
            assertThatThrownBy(() -> mutation.update(SITE_KEY, "digitall.example.com,victim.example.org"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("digitall.example.com, www.digitall.example.com")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("victim.example.org"));
            verify(store, never()).save(anyString(), anyList());
        }
    }

    @Test
    void update_hostsOfTheSite_areStoredCanonicallyForThatSite() throws IOException {
        // Arrange
        when(resolver.serverNamesOf(SITE_KEY)).thenReturn(Set.of("digitall.example.com", "www.digitall.example.com"));
        when(resolver.sitesClaiming("digitall.example.com")).thenReturn(Set.of(SITE_KEY));
        when(resolver.sitesClaiming("www.digitall.example.com")).thenReturn(Set.of(SITE_KEY));
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            services.when(SamlLoginProviderServices::siteResolver).thenReturn(resolver);
            services.when(SamlLoginProviderServices::siteHostsStore).thenReturn(store);
            services.when(SamlLoginProviderServices::samlSetup).thenReturn((SamlSetup) siteKey -> true);

            // Act
            GqlSamlLoginProviderConfig result = mutation.update(SITE_KEY, " Digitall.EXAMPLE.com , www.digitall.example.com ");

            // Assert
            verify(store).save(SITE_KEY, List.of("digitall.example.com", "www.digitall.example.com"));
            assertThat(result.getSiteKey()).isEqualTo(SITE_KEY);
            assertThat(result.getFqdn()).isEqualTo("digitall.example.com,www.digitall.example.com");
            assertThat(result.getServerNames()).containsExactly("digitall.example.com", "www.digitall.example.com");
        }
    }

    @Test
    void update_nullOrEmptyHostList_clearsTheSiteConfiguration() throws IOException {
        // Arrange
        when(resolver.serverNamesOf(SITE_KEY)).thenReturn(Set.of("digitall.example.com"));
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            services.when(SamlLoginProviderServices::siteResolver).thenReturn(resolver);
            services.when(SamlLoginProviderServices::siteHostsStore).thenReturn(store);
            services.when(SamlLoginProviderServices::samlSetup).thenReturn((SamlSetup) siteKey -> true);

            // Act
            GqlSamlLoginProviderConfig result = mutation.update(SITE_KEY, null);

            // Assert
            verify(store).save(SITE_KEY, List.of());
            assertThat(result.getFqdn()).isEmpty();
        }
    }

    @Test
    void update_hostSharedWithAnotherSite_isRejectedAndNothingIsStored() throws IOException {
        // Arrange: "localhost" is one of the site's names, but another site declares it too, so
        // Jahia may serve it from either: no single site's list can be allowed to govern it.
        when(resolver.serverNamesOf(SITE_KEY)).thenReturn(Set.of("localhost"));
        when(resolver.sitesClaiming("localhost")).thenReturn(Set.of(SITE_KEY, "otherSite"));
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            services.when(SamlLoginProviderServices::siteResolver).thenReturn(resolver);

            // Act / Assert: the other site is not named
            assertThatThrownBy(() -> mutation.update(SITE_KEY, "localhost"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("also declared by another site")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("otherSite"));
            verify(store, never()).save(anyString(), anyList());
        }
    }

    @Test
    void update_ioExceptionFromStore_surfacesAsIllegalStateExceptionWithGenericMessage() throws IOException {
        // Arrange
        when(resolver.serverNamesOf(SITE_KEY)).thenReturn(Set.of("digitall.example.com"));
        when(resolver.sitesClaiming("digitall.example.com")).thenReturn(Set.of(SITE_KEY));
        doThrow(new IOException("disk failure detail")).when(store).save(anyString(), any());
        try (MockedStatic<SamlLoginProviderPermissions> permissions = mockStatic(SamlLoginProviderPermissions.class);
             MockedStatic<SamlLoginProviderServices> services = mockStatic(SamlLoginProviderServices.class)) {
            services.when(SamlLoginProviderServices::siteResolver).thenReturn(resolver);
            services.when(SamlLoginProviderServices::siteHostsStore).thenReturn(store);
            services.when(SamlLoginProviderServices::samlSetup).thenReturn((SamlSetup) siteKey -> true);

            // Act / Assert
            assertThatThrownBy(() -> mutation.update(SITE_KEY, "digitall.example.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Unable to store the SAML login configuration")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("disk failure detail"));
        }
    }
}
