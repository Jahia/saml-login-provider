package org.jahia.community.modules.samlloginprovider.graphql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GqlSamlLoginProviderConfigTest {

    @Test
    void getFqdn_returnsValuePassedToConstructor() {
        // Arrange
        GqlSamlLoginProviderConfig config = new GqlSamlLoginProviderConfig("example.com,foo.example.com");

        // Act / Assert
        assertThat(config.getFqdn()).isEqualTo("example.com,foo.example.com");
    }
}
