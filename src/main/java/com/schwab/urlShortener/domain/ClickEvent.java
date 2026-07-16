package com.schwab.urlShortener.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "click_event")
@Getter
public class ClickEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, length = 16, updatable = false)
    private String shortCode;

    @Column(name = "clicked_at", nullable = false, updatable = false)
    private Instant clickedAt;

    @Column(name = "referrer", length = 512)
    private String referrer;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** SHA-256 of the client IP. Raw IPs are personal data; the hash lets us
     *  count unique visitors without storing the address. */
    @Column(name = "client_ip_hash", length = 64)
    private String clientIpHash;

    /** Required by JPA. Not for application use. */
    protected ClickEvent() {
    }

    public ClickEvent(String shortCode, Instant clickedAt, String referrer,
                      String userAgent, String clientIpHash) {
        this.shortCode = shortCode;
        this.clickedAt = clickedAt;
        this.referrer = truncate(referrer, 512);
        this.userAgent = truncate(userAgent, 512);
        this.clientIpHash = clientIpHash;
    }

    /**
     * Referrer and User-Agent come from HTTP headers, which are controlled by
     * the caller and effectively unbounded. Truncating here means an oversized
     * header records a shortened click rather than failing the insert.
     */
    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}