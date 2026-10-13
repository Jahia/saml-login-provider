package org.jahia.community.modules.samlloginprovider.graphql;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GqlSamlLoginProviderConfigTest {

    @Test
    void getters_returnTheValuesPassedToTheConstructor() {
        // Arrange
        GqlSamlLoginProviderConfig config = new GqlSamlLoginProviderConfig(
                "digitall", "example.com,foo.example.com", List.of("example.com", "foo.example.com"));

        // Act / Assert
        assertThat(config.getSiteKey()).isEqualTo("digitall");
        assertThat(config.getFqdn()).isEqualTo("example.com,foo.example.com");
        assertThat(config.getServerNames()).containsExactly("example.com", "foo.example.com");
    }

    @Test
    void serverNames_isADefensiveCopy() {
        // Arrange
        List<String> names = new ArrayList<>(List.of("example.com"));
        GqlSamlLoginProviderConfig config = new GqlSamlLoginProviderConfig("digitall", "", names);

        // Act
        names.add("mutated.example.com");

        // Assert
        assertThat(config.getServerNames()).containsExactly("example.com");
    }
}
