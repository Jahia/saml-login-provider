package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private static final String SITE_KEY = "mySite";
    private static final String SITE_PATH = "/sites/mySite";

    @Test
    void checkSiteAdmin_invalidSiteKey_throwsSecurityExceptionBeforeAnyJcrCall() {
        // Arrange / Act / Assert
        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            assertThatThrownBy(() -> SamlLoginProviderPermissions.checkSiteAdmin("../../evil"))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Invalid site key");

            // No JCR session was ever requested for an invalid key
            jcrSessionFactory.verifyNoInteractions();
        }
    }

    @Test
    void checkSiteAdmin_nullSiteKey_throwsSecurityExceptionBeforeAnyJcrCall() {
        // Arrange / Act / Assert
        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            assertThatThrownBy(() -> SamlLoginProviderPermissions.checkSiteAdmin(null))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Invalid site key");

            jcrSessionFactory.verifyNoInteractions();
        }
    }

    @Test
    void checkSiteAdmin_validSiteKeyAndHasPermission_passesSilently(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session,
            @Mock JCRNodeWrapper node) throws RepositoryException {
        // Arrange
        when(session.nodeExists(SITE_PATH)).thenReturn(true);
        when(session.getNode(SITE_PATH)).thenReturn(node);
        when(node.hasPermission(SamlLoginProviderPermissions.PERMISSION)).thenReturn(true);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatCode(() -> SamlLoginProviderPermissions.checkSiteAdmin(SITE_KEY)).doesNotThrowAnyException();
        }
    }

    @Test
    void checkSiteAdmin_nodeMissing_throwsSecurityException(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session) throws RepositoryException {
        // Arrange
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

    @Test
    void checkSiteAdmin_hasPermissionFalse_throwsSecurityException(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session,
            @Mock JCRNodeWrapper node) throws RepositoryException {
        // Arrange
        when(session.nodeExists(SITE_PATH)).thenReturn(true);
        when(session.getNode(SITE_PATH)).thenReturn(node);
        when(node.hasPermission(SamlLoginProviderPermissions.PERMISSION)).thenReturn(false);
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
    void checkSiteAdmin_repositoryExceptionFromSession_wrappedAsIllegalStateException(
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
                    .hasMessageContaining(SITE_KEY)
                    .hasCause(repositoryException);
        }
    }
}
