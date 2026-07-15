package com.schwab.urlShortener.service;

import com.schwab.urlShortener.domain.ShortLink;

import java.time.Instant;

/**
 * Immutable cache value. Deliberately NOT the JPA entity: a cached entity is
 * detached from the persistence context, so lazy access fails and Hibernate
 * may attempt reattachment.
 *
 * Stores expiresAt/active as data rather than caching a resolvable/not
 * decision, so expiry is evaluated fresh on every read.
 */
public record CachedLink(String shortCode, String longUrl, Instant expiresAt, boolean active) {

    public static CachedLink from(ShortLink link) {
        return new CachedLink(link.getShortCode(), link.getLongUrl(),
                link.getExpiresAt(), link.isActive());
    }

    public boolean isResolvable(Instant now) {
        return active && (expiresAt == null || now.isBefore(expiresAt));
    }
}