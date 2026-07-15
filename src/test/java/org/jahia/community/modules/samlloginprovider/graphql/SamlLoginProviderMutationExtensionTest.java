package org.jahia.community.modules.samlloginprovider.graphql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SamlLoginProviderMutationExtensionTest {

    @Test
    void samlLoginProvider_returnsNewMutationNamespaceInstance() {
        // Act
        SamlLoginProviderMutation result = SamlLoginProviderMutationExtension.samlLoginProvider();

        // Assert: a fresh, independent instance is returned each time (no shared mutable state)
        assertThat(result).isNotNull().isNotSameAs(SamlLoginProviderMutationExtension.samlLoginProvider());
    }
}
