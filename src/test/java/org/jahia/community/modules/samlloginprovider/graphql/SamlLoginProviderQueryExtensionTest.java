package org.jahia.community.modules.samlloginprovider.graphql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SamlLoginProviderQueryExtensionTest {

    @Test
    void samlLoginProvider_returnsNewQueryNamespaceInstance() {
        // Act
        SamlLoginProviderQuery result = SamlLoginProviderQueryExtension.samlLoginProvider();

        // Assert: a fresh, independent instance is returned each time (no shared mutable state)
        assertThat(result).isNotNull().isNotSameAs(SamlLoginProviderQueryExtension.samlLoginProvider());
    }
}
