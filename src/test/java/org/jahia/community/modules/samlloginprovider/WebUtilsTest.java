package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class WebUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {"site1", "my-site", "my_site", "Site123", "a", "0site", "site-with-many_parts-2"})
    void isValidSiteKey_validKeys_returnsTrue(String siteKey) {
        // Act / Assert
        assertThat(WebUtils.isValidSiteKey(siteKey)).isTrue();
    }

    @Test
    void isValidSiteKey_null_returnsFalse() {
        // Act / Assert
        assertThat(WebUtils.isValidSiteKey(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"_site", "_hidden", "__PLACEHOLDER__"})
    void isValidSiteKey_startsWithUnderscore_returnsFalse(String siteKey) {
        // Act / Assert
        assertThat(WebUtils.isValidSiteKey(siteKey)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-site", ".site", "/site", " site", "<site"})
    void isValidSiteKey_startsWithNonAlphanumeric_returnsFalse(String siteKey) {
        // Act / Assert
        assertThat(WebUtils.isValidSiteKey(siteKey)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>alert(1)</script>", "site\"onmouseover", "site name", "site/path", "site.com"})
    void isValidSiteKey_markupOrWhitespaceOrSeparators_returnsFalse(String siteKey) {
        // Act / Assert
        assertThat(WebUtils.isValidSiteKey(siteKey)).isFalse();
    }

    @Test
    void isValidSiteKey_emptyString_returnsFalse() {
        // Act / Assert
        assertThat(WebUtils.isValidSiteKey("")).isFalse();
    }

    @Test
    void urlEncode_spaceCharacter_encodedAsPlus() {
        // Act / Assert
        assertThat(WebUtils.urlEncode("a b")).isEqualTo("a+b");
    }

    @Test
    void urlEncode_specialCharacters_percentEncoded() {
        // Act
        String result = WebUtils.urlEncode("/foo?bar=baz&x=<y>");

        // Assert
        assertThat(result).isEqualTo("%2Ffoo%3Fbar%3Dbaz%26x%3D%3Cy%3E");
    }

    @Test
    void urlEncode_unicodeCharacters_utf8PercentEncoded() {
        // Act
        String result = WebUtils.urlEncode("café");

        // Assert: 'é' is 2 bytes in UTF-8 -> %C3%A9
        assertThat(result).isEqualTo("caf%C3%A9");
    }

    @Test
    void urlEncode_plainAlphanumeric_isUnchanged() {
        // Act / Assert
        assertThat(WebUtils.urlEncode("plainValue123")).isEqualTo("plainValue123");
    }
}
