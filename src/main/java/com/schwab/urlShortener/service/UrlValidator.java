package com.schwab.urlShortener.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

/**
 * Validates that a submitted URL is safe to store and later redirect to.
 *
 * Scheme allowlist (not blocklist): a blocklist can always be bypassed with a
 * scheme nobody thought of. javascript: would give stored XSS on redirect;
 * file: would expose local files.
 *
 * LIMITATION: does not block URLs resolving to internal addresses
 * (169.254.169.254, 10.x, localhost). Correct handling requires DNS resolution
 * at redirect time, since DNS can change after validation. Documented, not fixed.
 */
@Component
public class UrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final int MAX_LENGTH = 2048;

    public void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new InvalidUrlException("URL must not be empty");
        }
        if (url.length() > MAX_LENGTH) {
            throw new InvalidUrlException("URL exceeds " + MAX_LENGTH + " characters");
        }

        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("URL is malformed");
        }

        if (!uri.isAbsolute()) {
            throw new InvalidUrlException("URL must be absolute and include a scheme");
        }

        String scheme = uri.getScheme().toLowerCase();
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new InvalidUrlException("Scheme '" + scheme + "' is not allowed; use http or https");
        }

        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidUrlException("URL must include a host");
        }
    }
}