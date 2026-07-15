package com.schwab.urlShortener.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {
    // Cache tuning lives in application.yml (spring.cache.caffeine.spec).
}