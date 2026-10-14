package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ButtonLabelTest {

    @Test
    void normalize_trims_andTreatsNullAsTheDefault() {
        assertThat(ButtonLabel.normalize("  Sign in with SSO  ")).isEqualTo("Sign in with SSO");
        assertThat(ButtonLabel.normalize(null)).isEmpty();
        assertThat(ButtonLabel.normalize("   ")).isEmpty();
        // Unicode whitespace too, as the settings screen's trim() does
        assertThat(ButtonLabel.normalize("\u00A0Sign in\u2003")).isEqualTo("Sign in");
        assertThat(ButtonLabel.normalize("\uFEFFSign in\t\n")).isEqualTo("Sign in");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Sign in with SSO", "Se connecter avec Google", "Anmelden über SSO — ÄÖÜ", "日本語でログイン"})
    void isValid_acceptsPlainText(String label) {
        assertThat(ButtonLabel.isValid(label)).isTrue();
    }

    @Test
    void isValid_acceptsTheMaximumLength_only() {
        assertThat(ButtonLabel.isValid("x".repeat(ButtonLabel.MAX_LENGTH))).isTrue();
        assertThat(ButtonLabel.isValid("x".repeat(ButtonLabel.MAX_LENGTH + 1))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\rb", "a\tb", "a\u0000b", "a\u2028b", "a\u2029b",
            "a\u200Bb", "a\u202Eb", "a\uFEFFb", "\u200B"})
    void isValid_rejectsControlFormatAndSeparatorCharacters(String label) {
        // Format characters (zero-width, bidi overrides) would make the button blank or spoofed
        assertThat(ButtonLabel.isValid(label)).isFalse();
    }

    @Test
    void isValid_null_isNot() {
        assertThat(ButtonLabel.isValid(null)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sign in", "Connexion \"SSO\" [${env}]", "é+%&=#", "日本語"})
    void encode_roundTrips_throughPlainCfgCharacters(String label) {
        String encoded = ButtonLabel.encode(label);

        assertThat(encoded).matches("[A-Za-z0-9%+._*-]*");
        assertThat(ButtonLabel.decode(encoded)).isEqualTo(label);
    }

    @Test
    void decode_handWrittenPlainText_isKept() {
        assertThat(ButtonLabel.decode("Sign in with SSO")).isEqualTo("Sign in with SSO");
    }

    @Test
    void decode_brokenEscape_isRejected() {
        assertThatThrownBy(() -> ButtonLabel.decode("%zz")).isInstanceOf(IllegalArgumentException.class);
    }
}
