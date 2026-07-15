package com.schwab.urlShortener.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "short_link")
@Getter
public class ShortLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "short_code", nullable = false, unique = true, length = 16, updatable = false)
    private String shortCode;

    @Column(name = "long_url", nullable = false, length = 2048)
    private String longUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    /** Required by JPA. Not for application use. */
    protected ShortLink() {
    }

    public ShortLink(String shortCode, String longUrl, Instant createdAt, Instant expiresAt) {
        this.shortCode = Objects.requireNonNull(shortCode, "shortCode");
        this.longUrl = Objects.requireNonNull(longUrl, "longUrl");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isResolvable(Instant now) {
        return active && !isExpired(now);
    }

    public void deactivate() {
        this.active = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShortLink other)) return false;
        return shortCode != null && shortCode.equals(other.shortCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(shortCode);
    }
}