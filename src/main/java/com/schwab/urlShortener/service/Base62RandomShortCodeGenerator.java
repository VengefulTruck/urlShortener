package com.schwab.urlShortener.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates non-sequential, non-guessable short codes.
 *
 * Random rather than counter-derived: Base62-encoding an auto-increment ID
 * produces enumerable codes, letting anyone walk /r/1, /r/2, ... and harvest
 * every link, and infer link-creation volume from a single code.
 *
 * Collisions are possible but rare (62^7 keyspace, ~1 in 350,000 at 10M links)
 * and are handled by the DB unique constraint plus retry in the service layer.
 */
@Component
public class Base62RandomShortCodeGenerator implements ShortCodeGenerator {

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private final SecureRandom random = new SecureRandom();
    private final int length;

    public Base62RandomShortCodeGenerator(@Value("${app.short-code-length}") int length) {
        if (length < 4 || length > 16) {
            throw new IllegalArgumentException("short-code-length must be 4..16, was " + length);
        }
        this.length = length;
    }

    @Override
    public String generate() {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            // nextInt(bound) is unbiased; nextInt() % 62 would skew toward early chars.
            buf[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(buf);
    }
}