package com.schwab.urlShortener.service;

import com.schwab.urlShortener.repository.ShortLinkRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean, not a method on ShortLinkService, because @Cacheable works
 * through a Spring proxy: a self-invocation inside ShortLinkService would
 * bypass the proxy and silently never cache.
 */
@Component
public class ShortLinkLookup {

    private final ShortLinkRepository repository;

    public ShortLinkLookup(ShortLinkRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns null (cached) for unknown codes. Negative caching is deliberate:
     * without it, enumeration attempts hit the DB on every request. Caffeine's
     * bounded size plus its admission policy limit the resulting cache churn.
     */
    @Cacheable(value = "shortLinks", unless = "false")
    @Transactional(readOnly = true)
    public CachedLink find(String shortCode) {
        return repository.findByShortCode(shortCode)
                .map(CachedLink::from)
                .orElse(null);
    }

    @CacheEvict(value = "shortLinks", key = "#shortCode")
    public void evict(String shortCode) {
        // Hook for deactivation/mutation paths.
    }
}