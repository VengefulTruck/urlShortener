package com.schwab.urlShortener.service;

import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.repository.ShortLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class ShortLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortLinkService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final ShortLinkRepository repository;
    private final ShortCodeGenerator generator;
    private final UrlValidator urlValidator;
    private final ShortLinkLookup lookup;
    private final Clock clock;

    public ShortLinkService(ShortLinkRepository repository,
                            ShortCodeGenerator generator,
                            UrlValidator urlValidator,
                            ShortLinkLookup lookup,
                            Clock clock) {
        this.repository = repository;
        this.generator = generator;
        this.urlValidator = urlValidator;
        this.lookup = lookup;
        this.clock = clock;
    }

    /**
     * Creates a short link.
     *
     * Does NOT check code availability before inserting: any such check is a
     * TOCTOU race, since another thread can insert between check and save.
     * The unique index on short_code is the only atomic arbiter, so we attempt
     * the insert and treat rejection as the signal to retry.
     */
    @Transactional
    public ShortLink create(String longUrl, Instant expiresAt) {
        urlValidator.validate(longUrl);

        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new InvalidUrlException("expiresAt must be in the future");
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String code = generator.generate();
            try {
                // saveAndFlush, not save: forces the INSERT now so the constraint
                // violation surfaces here and can be retried. save() would defer
                // the SQL to commit time, outside this try block.
                ShortLink saved = repository.saveAndFlush(
                        new ShortLink(code, longUrl, now, expiresAt));
                log.debug("Created short link {} on attempt {}", code, attempt);
                return saved;
            } catch (DataIntegrityViolationException e) {
                log.warn("Short code collision on '{}', attempt {}/{}", code, attempt, MAX_ATTEMPTS);
            }
        }

        // 5 consecutive collisions is statistically implausible; it means the
        // keyspace is exhausted or the generator is broken. Fail loudly.
        throw new ShortCodeGenerationException(
                "Could not generate a unique short code after " + MAX_ATTEMPTS + " attempts");
    }

    /**
     * Uncached. Serves the metadata API, which needs the full entity.
     */
    @Transactional(readOnly = true)
    public ShortLink resolve(String shortCode) {
        return repository.findByShortCode(shortCode)
                .filter(link -> link.isResolvable(clock.instant()))
                .orElseThrow(() -> new ShortLinkNotFoundException(shortCode));
    }

    /**
     * Redirect hot path. Cached via ShortLinkLookup.
     *
     * No @Transactional here: the lookup bean manages its own transaction, and
     * on a cache hit no database access occurs at all — an outer transaction
     * would take a connection from the pool for nothing.
     */
    public String resolveTarget(String shortCode) {
        CachedLink link = lookup.find(shortCode);
        if (link == null || !link.isResolvable(clock.instant())) {
            throw new ShortLinkNotFoundException(shortCode);
        }
        return link.longUrl();
    }
}