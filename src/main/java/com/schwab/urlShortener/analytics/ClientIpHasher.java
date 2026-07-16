package com.schwab.urlShortener.analytics;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Turns a visitor's IP into a one-way hash, so unique visitors can be counted
 * without storing personal data.
 *
 * LIMITATION: an unsalted SHA-256 of an IPv4 address can be brute-forced —
 * there are only ~4 billion possible addresses, so an attacker can hash them
 * all and match. A production version needs a rotating salt held outside the
 * database. Documented, not implemented.
 */
@Component
public class ClientIpHasher {

    public String hash(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (ip == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}