package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.jcr.RepositoryException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SamlLoginProviderPermissionsTest {

    private static final String SITE_KEY = "digitall";
    private static final String SITE_PATH = "/sites/digitall";

    @Test
    void checkSiteAdmin_hasPermissionOnTheSite_passesSilently(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session,
            @Mock JCRNodeWrapper siteNode) throws RepositoryException {
        // Arrange
        when(session.nodeExists(SITE_PATH)).thenReturn(true);
        when(session.getNode(SITE_PATH)).thenReturn(siteNode);
        when(siteNode.hasPermission(SamlLoginProviderPermissions.PERMISSION)).thenReturn(true);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatCode(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY)).doesNotThrowAnyException();
        }
    }

    @Test
    void checkSiteAdmin_noPermissionOnTheSite_throwsSecurityException(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session,
            @Mock JCRNodeWrapper siteNode) throws RepositoryException {
        // Arrange
        when(session.nodeExists(SITE_PATH)).thenReturn(true);
        when(session.getNode(SITE_PATH)).thenReturn(siteNode);
        when(siteNode.hasPermission(SamlLoginProviderPermissions.PERMISSION)).thenReturn(false);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatThrownBy(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied");
        }
    }

    @Test
    void checkSiteAdmin_siteMissingOrNotReadable_isDeniedLikeAnyOtherSite(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session) throws RepositoryException {
        // Arrange: same answer as "no permission", so the API cannot be used to enumerate sites
        when(session.nodeExists(SITE_PATH)).thenReturn(false);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatThrownBy(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../digitall", "a/b", "_x"})
    void checkSiteAdmin_invalidSiteKey_isRejectedBeforeAnyJcrAccess(String siteKey) {
        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            // Act / Assert
            assertThatThrownBy(() -> SamlLoginProviderPermissions.checkSiteAdmin(siteKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid site key");
            jcrSessionFactory.verifyNoInteractions();
        }
    }

    @Test
    void checkSiteAdmin_repositoryException_wrappedAsIllegalStateException(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session) throws RepositoryException {
        // Arrange
        RepositoryException repositoryException = new RepositoryException("JCR unavailable");
        when(session.nodeExists(SITE_PATH)).thenThrow(repositoryException);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatThrownBy(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Site-level permission check failed")
                    .hasCause(repositoryException);
        }
    }
}
