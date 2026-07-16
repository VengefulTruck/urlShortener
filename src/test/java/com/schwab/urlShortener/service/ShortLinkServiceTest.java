package com.schwab.urlShortener.service;

import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.repository.ShortLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkServiceTest {

    /** Fixed point in time. Nothing in these tests depends on the real clock. */
    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @Mock private ShortLinkRepository repository;
    @Mock private ShortCodeGenerator generator;
    @Mock private ShortLinkLookup lookup;

    private ShortLinkService service;

    @BeforeEach
    void setUp() {
        service = new ShortLinkService(
                repository,
                generator,
                new UrlValidator(),                       // real: cheap and already tested
                lookup,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---------- create ----------

    @Test
    @DisplayName("creates a link on the first attempt when there is no collision")
    void createsOnFirstAttempt() {
        when(generator.generate()).thenReturn("abc1234");
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortLink result = service.create("https://example.com", null);

        assertThat(result.getShortCode()).isEqualTo("abc1234");
        assertThat(result.getLongUrl()).isEqualTo("https://example.com");
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
        verify(generator, times(1)).generate();
    }

    /**
     * The core of the TOCTOU design. The database rejects a duplicate short_code
     * via its unique index; the service must treat that as "try again", not as
     * an error. Impossible to trigger by hand - the odds are ~1 in 350,000.
     */
    @Test
    @DisplayName("retries with a new code when the database rejects a duplicate")
    void retriesOnCollision() {
        when(generator.generate()).thenReturn("taken1", "free567");
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(inv -> inv.getArgument(0));

        ShortLink result = service.create("https://example.com", null);

        assertThat(result.getShortCode()).isEqualTo("free567");
        verify(generator, times(2)).generate();
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test
    @DisplayName("survives several consecutive collisions")
    void retriesMultipleTimes() {
        when(generator.generate()).thenReturn("c1", "c2", "c3", "c4", "ok55555");
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create("https://example.com", null).getShortCode())
                .isEqualTo("ok55555");
        verify(generator, times(5)).generate();
    }

    /**
     * Bounded retries. Five failures in a row cannot happen by chance, so it
     * means the generator is broken or the keyspace is exhausted. The service
     * must give up loudly rather than loop forever.
     */
    @Test
    @DisplayName("gives up after the retry limit rather than looping forever")
    void failsAfterRetryLimit() {
        when(generator.generate()).thenReturn("always1");
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create("https://example.com", null))
                .isInstanceOf(ShortCodeGenerationException.class)
                .hasMessageContaining("5 attempts");

        verify(generator, times(5)).generate();
    }

    @Test
    @DisplayName("validates the URL before touching the database")
    void validatesBeforePersisting() {
        assertThatThrownBy(() -> service.create("javascript:alert(1)", null))
                .isInstanceOf(InvalidUrlException.class);

        // The security control must run first: nothing dangerous reaches storage.
        verifyNoInteractions(repository, generator);
    }

    @Test
    @DisplayName("rejects an expiry in the past")
    void rejectsPastExpiry() {
        assertThatThrownBy(() ->
                service.create("https://example.com", NOW.minusSeconds(1)))
                .isInstanceOf(InvalidUrlException.class)
                .hasMessageContaining("future");

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("accepts an expiry in the future")
    void acceptsFutureExpiry() {
        Instant expiry = NOW.plusSeconds(3600);
        when(generator.generate()).thenReturn("abc1234");
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create("https://example.com", expiry).getExpiresAt())
                .isEqualTo(expiry);
    }

    // ---------- resolveTarget ----------

    @Test
    @DisplayName("resolves an active link to its target URL")
    void resolvesActiveLink() {
        when(lookup.find("abc1234"))
                .thenReturn(new CachedLink("abc1234", "https://example.com", null, true));

        assertThat(service.resolveTarget("abc1234")).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("throws when the code is unknown")
    void throwsWhenNotFound() {
        when(lookup.find("nope123")).thenReturn(null);

        assertThatThrownBy(() -> service.resolveTarget("nope123"))
                .isInstanceOf(ShortLinkNotFoundException.class);
    }

    /**
     * Expiry proven with a fixed clock rather than Thread.sleep. This is why
     * Clock is injected: the test moves time instead of waiting for it.
     */
    @Test
    @DisplayName("treats an expired link as not found")
    void expiredLinkIsNotFound() {
        when(lookup.find("expired"))
                .thenReturn(new CachedLink("expired", "https://example.com",
                        NOW.minusSeconds(1), true));

        assertThatThrownBy(() -> service.resolveTarget("expired"))
                .isInstanceOf(ShortLinkNotFoundException.class);
    }

    @Test
    @DisplayName("resolves a link whose expiry has not yet passed")
    void unexpiredLinkResolves() {
        when(lookup.find("valid12"))
                .thenReturn(new CachedLink("valid12", "https://example.com",
                        NOW.plusSeconds(1), true));

        assertThat(service.resolveTarget("valid12")).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("treats a deactivated link as not found")
    void deactivatedLinkIsNotFound() {
        when(lookup.find("gone123"))
                .thenReturn(new CachedLink("gone123", "https://example.com", null, false));

        assertThatThrownBy(() -> service.resolveTarget("gone123"))
                .isInstanceOf(ShortLinkNotFoundException.class);
    }

    // ---------- resolve (metadata path) ----------

    @Test
    @DisplayName("resolve() reads the entity directly, bypassing the cache")
    void resolveReadsEntity() {
        ShortLink link = new ShortLink("abc1234", "https://example.com", NOW, null);
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(link));

        assertThat(service.resolve("abc1234")).isSameAs(link);
        verifyNoInteractions(lookup);
    }
}