package com.schwab.urlShortener.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Base62RandomShortCodeGeneratorTest {

    private static final Pattern BASE62 = Pattern.compile("^[0-9A-Za-z]+$");

    @Test
    @DisplayName("generates a code of the configured length")
    void generatesConfiguredLength() {
        ShortCodeGenerator generator = new Base62RandomShortCodeGenerator(7);

        assertThat(generator.generate()).hasSize(7);
    }

    @Test
    @DisplayName("generates codes using only Base62 characters")
    void usesOnlyBase62Characters() {
        ShortCodeGenerator generator = new Base62RandomShortCodeGenerator(7);

        for (int i = 0; i < 1000; i++) {
            assertThat(generator.generate()).matches(BASE62);
        }
    }

    /**
     * Not a proof of uniqueness - randomness cannot be proven by sampling.
     * This is a smoke test: a broken generator (constant, tiny alphabet, or
     * seeded identically) would collide heavily here.
     */
    @Test
    @DisplayName("produces distinct codes across many calls")
    void producesDistinctCodes() {
        ShortCodeGenerator generator = new Base62RandomShortCodeGenerator(7);
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            seen.add(generator.generate());
        }

        assertThat(seen).hasSize(10_000);
    }

    @Test
    @DisplayName("rejects a length below the minimum at construction time")
    void rejectsTooShort() {
        assertThatThrownBy(() -> new Base62RandomShortCodeGenerator(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4..16");
    }

    @Test
    @DisplayName("rejects a length above the maximum at construction time")
    void rejectsTooLong() {
        assertThatThrownBy(() -> new Base62RandomShortCodeGenerator(17))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4..16");
    }

    @Test
    @DisplayName("honours a non-default length")
    void honoursCustomLength() {
        assertThat(new Base62RandomShortCodeGenerator(10).generate()).hasSize(10);
    }
}