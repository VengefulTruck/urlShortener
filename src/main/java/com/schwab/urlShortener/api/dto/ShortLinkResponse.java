package com.schwab.urlShortener.api.dto;

import com.schwab.urlShortener.domain.ShortLink;

import java.time.Instant;

public record ShortLinkResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt
) {
    public static ShortLinkResponse from(ShortLink link, String baseUrl, String redirectPath) {
        return new ShortLinkResponse(
                link.getShortCode(),
                baseUrl + redirectPath + "/" + link.getShortCode(),
                link.getLongUrl(),
                link.getCreatedAt(),
                link.getExpiresAt()
        );
    }
}