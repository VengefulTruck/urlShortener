package com.schwab.urlShortener.api.dto;

public record LinkStatsResponse(
        String shortCode,
        long totalClicks,
        long clicksLast24h,
        long uniqueVisitors
) {}