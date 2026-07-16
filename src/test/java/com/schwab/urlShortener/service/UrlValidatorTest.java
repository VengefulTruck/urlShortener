package com.schwab.urlShortener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private UrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UrlValidator();
    }

    // ---------- accepted ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",
            "https://example.com",
            "https://example.com/path/to/thing",
            "https://example.com:8443/path?query=1&other=2#fragment",
            "https://sub.domain.example.co.uk/a/b/c",
            "HTTPS://EXAMPLE.COM"          // scheme comparison must be case-insensitive
    })
    @DisplayName("accepts well-formed http and https URLs")
    void acceptsValidUrls(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    // ---------- the security control ----------

    /**
     * The reason this class exists. Each of these is a real attack:
     *   javascript: - stored XSS, executes in the victim's browser on redirect
     *   data:       - same, via an inline payload
     *   file:       - reads the server's local filesystem
     *   ftp/gopher  - unexpected protocols we never intended to proxy
     *
     * The validator uses an allowlist, so this list is illustrative, not
     * exhaustive - anything outside http/https is rejected by construction.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(document.cookie)",
            "JavaScript:alert(1)",                    // case must not bypass it
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "ftp://example.com/file.txt",
            "gopher://example.com"
    })
    @DisplayName("rejects every scheme outside the http/https allowlist")
    void rejectsDangerousSchemes(String url) {
        // Asserts the outcome (rejected), not the mechanism. Some payloads are
        // caught by the scheme allowlist, others fail URI parsing first because
        // they contain characters illegal in a URI. Either is a correct reject;
        // pinning the message would couple the test to which rule fires.
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidUrlException.class);
    }

    // ---------- malformed input ----------

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("rejects empty and whitespace-only input")
    void rejectsBlank(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("rejects a relative URL with no scheme")
    void rejectsRelativeUrl() {
        assertThatThrownBy(() -> validator.validate("www.example.com/path"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    @DisplayName("rejects a URL with a scheme but no host")
    void rejectsMissingHost() {
        assertThatThrownBy(() -> validator.validate("http://"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @DisplayName("rejects a malformed URL")
    void rejectsMalformed() {
        assertThatThrownBy(() -> validator.validate("http://exa mple.com"))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("malformed");
    }

    // ---------- boundaries ----------

    @Test
    @DisplayName("accepts a URL at exactly the length limit")
    void acceptsUrlAtLimit() {
        String url = "https://example.com/" + "a".repeat(2048 - 20);

        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a URL one character over the limit")
    void rejectsUrlOverLimit() {
        String url = "https://example.com/" + "a".repeat(2049 - 20);

        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("2048");
    }

    // ---------- known gap, documented not fixed ----------

    /**
     * Pins current behaviour rather than asserting it is correct. Internal
     * addresses ARE accepted today: blocking them properly needs DNS resolution
     * at redirect time, since a hostname can be repointed after validation.
     *
     * If someone later adds SSRF protection, this test fails - which is the
     * intent. It is a marker, not an endorsement.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://localhost:8080/actuator/env",
            "http://10.0.0.1/internal"
    })
    @DisplayName("KNOWN GAP: internal addresses are not blocked")
    void documentsSsrfGap(String url) {
        assertThatCode(() -> validator.validate(url)).doesNotThrowAnyException();
    }
}