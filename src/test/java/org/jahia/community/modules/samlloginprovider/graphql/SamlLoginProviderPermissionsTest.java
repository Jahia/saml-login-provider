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

    @Test
    void checkServerAdmin_hasPermission_passesSilently(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session,
            @Mock JCRNodeWrapper rootNode) throws RepositoryException {
        // Arrange
        when(session.getNode("/")).thenReturn(rootNode);
        when(rootNode.hasPermission(SamlLoginProviderPermissions.PERMISSION)).thenReturn(true);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatCode(SamlLoginProviderPermissions::checkServerAdmin).doesNotThrowAnyException();
        }
    }

    @Test
    void checkServerAdmin_hasPermissionFalse_throwsSecurityException(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session,
            @Mock JCRNodeWrapper rootNode) throws RepositoryException {
        // Arrange
        when(session.getNode("/")).thenReturn(rootNode);
        when(rootNode.hasPermission(SamlLoginProviderPermissions.PERMISSION)).thenReturn(false);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatThrownBy(SamlLoginProviderPermissions::checkServerAdmin)
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("Access denied");
        }
    }

    @Test
    void checkServerAdmin_repositoryExceptionFromSession_wrappedAsIllegalStateException(
            @Mock JCRSessionFactory sessionFactory,
            @Mock JCRSessionWrapper session) throws RepositoryException {
        // Arrange
        RepositoryException repositoryException = new RepositoryException("JCR unavailable");
        when(session.getNode("/")).thenThrow(repositoryException);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        try (MockedStatic<JCRSessionFactory> jcrSessionFactory = mockStatic(JCRSessionFactory.class)) {
            jcrSessionFactory.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act / Assert
            assertThatThrownBy(SamlLoginProviderPermissions::checkServerAdmin)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Server-level permission check failed")
                    .hasCause(repositoryException);
        }
    }
}
