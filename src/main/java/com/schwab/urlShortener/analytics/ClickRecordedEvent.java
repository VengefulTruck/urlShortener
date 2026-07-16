package com.schwab.urlShortener.analytics;

import java.time.Instant;

/**
 * The "someone clicked" message.
 *
 * Carries plain values, not a database entity: the listener runs on a different
 * thread with no database session, so an entity handed across would be unusable.
 */
public record ClickRecordedEvent(
        String shortCode,
        Instant clickedAt,
        String referrer,
        String userAgent,
        String clientIpHash
) {}