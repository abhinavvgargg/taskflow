package com.abhinav.taskflow.common.util;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizeTest {

    @Test
    void normalizeEmail_trimsAndLowercases() {
        assertThat(Normalize.normalizeEmail("  Alice@Example.COM \t")).isEqualTo("alice@example.com");
    }

    @Test
    void normalizeEmail_null_staysNull() {
        assertThat(Normalize.normalizeEmail(null)).isNull();
    }

    @Test
    void normalizeEmail_isNotAffectedByTheJvmDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            // The trap, demonstrated: a plain toLowerCase() under Turkish turns 'I' into dotless 'ı'.
            assertThat("TITLE@EXAMPLE.COM".toLowerCase()).isEqualTo("tıtle@example.com");

            // Normalize uses Locale.ROOT, so the result is the same on every server.
            assertThat(Normalize.normalizeEmail("TITLE@EXAMPLE.COM")).isEqualTo("title@example.com");
        } finally {
            Locale.setDefault(original);   // the default locale is JVM-wide: always put it back
        }
    }
}
