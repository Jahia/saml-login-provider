package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HostListTest {

    @Test
    void parse_commaSeparatedValues_trimsAndLowercases() {
        // Arrange
        String value = " Example.COM ,sub.Example.com,  Site.ORG ";

        // Act
        List<String> result = HostList.parse(value);

        // Assert
        assertThat(result).containsExactly("example.com", "sub.example.com", "site.org");
    }

    @Test
    void parse_emptyEntriesBetweenCommas_areFiltered() {
        // Arrange
        String value = "example.com,,  ,foo.example.com";

        // Act
        List<String> result = HostList.parse(value);

        // Assert
        assertThat(result).containsExactly("example.com", "foo.example.com");
    }

    @Test
    void parse_emptyString_returnsEmptyList() {
        // Act
        List<String> result = HostList.parse("");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void parse_blankString_returnsEmptyList() {
        // Act
        List<String> result = HostList.parse("   ");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void isValid_emptyList_isValid() {
        // Act / Assert
        assertThat(HostList.isValid(Collections.emptyList())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com",
            "sub.example.co.uk",
            "localhost",
            "a.b.c.d.e.f",
            "xn--80akhbyknj4f.example",
            "single-label-host"
    })
    void isValid_validHostnames_returnsTrue(String host) {
        // Act / Assert
        assertThat(HostList.isValid(List.of(host))).isTrue();
    }

    @Test
    void isValid_ipv4LiteralLabels_areAcceptedAsHostnames() {
        // Act / Assert: numeric labels are not range-checked (0-255), only shape-checked
        assertThat(HostList.isValid(List.of("192.168.1.1"))).isTrue();
        assertThat(HostList.isValid(List.of("999.999.999.999"))).isTrue();
    }

    @Test
    void isValid_labelExceeding63Characters_returnsFalse() {
        // Arrange
        String tooLongLabel = "a".repeat(64);

        // Act / Assert
        assertThat(HostList.isValid(List.of(tooLongLabel + ".com"))).isFalse();
    }

    @Test
    void isValid_labelWith63Characters_returnsTrue() {
        // Arrange
        String maxLabel = "a".repeat(63);

        // Act / Assert
        assertThat(HostList.isValid(List.of(maxLabel + ".com"))).isTrue();
    }

    @Test
    void isValid_hostnameExceeding253Characters_returnsFalse() {
        // Arrange: 4 labels of 63 chars joined by dots exceeds 253 characters
        String label = "a".repeat(63);
        String tooLong = String.join(".", label, label, label, label);

        // Act / Assert
        assertThat(tooLong.length()).isGreaterThan(253);
        assertThat(HostList.isValid(List.of(tooLong))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-example.com",
            "example-.com",
            "example.-com",
            "example.com-"
    })
    void isValid_labelWithLeadingOrTrailingHyphen_returnsFalse(String host) {
        // Act / Assert
        assertThat(HostList.isValid(List.of(host))).isFalse();
    }

    @Test
    void isValid_trailingDotAbsoluteFqdnNotation_returnsFalse() {
        // Act / Assert: getServerName() never returns the trailing-dot form, so it must be rejected
        assertThat(HostList.isValid(List.of("example.com."))).isFalse();
    }

    @Test
    void isValid_emptyHostname_returnsFalse() {
        // Act / Assert
        assertThat(HostList.isValid(List.of(""))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "exa mple.com",
            "example.com/path",
            "example.com?x=1",
            "exam=ple.com",
            "exam_ple.com",
            "a..b"
    })
    void isValid_invalidCharactersOrEmptyLabels_returnsFalse(String host) {
        // Act / Assert
        assertThat(HostList.isValid(List.of(host))).isFalse();
    }

    @Test
    void isValid_mixedValidAndInvalidHosts_returnsFalse() {
        // Act / Assert
        assertThat(HostList.isValid(List.of("example.com", "-bad.com"))).isFalse();
    }

    @Test
    void canonical_multipleHosts_joinsWithCommaNoSpaces() {
        // Act
        String result = HostList.canonical(List.of("example.com", "foo.example.com"));

        // Assert
        assertThat(result).isEqualTo("example.com,foo.example.com");
    }

    @Test
    void canonical_emptyList_returnsEmptyString() {
        // Act
        String result = HostList.canonical(Collections.emptyList());

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void canonical_singleHost_returnsHostUnchanged() {
        // Act
        String result = HostList.canonical(List.of("example.com"));

        // Assert
        assertThat(result).isEqualTo("example.com");
    }
}
