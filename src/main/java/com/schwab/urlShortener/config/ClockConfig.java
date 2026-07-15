package com.schwab.urlShortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /**
     * Injected rather than calling Instant.now() directly: expiry logic is
     * untestable without controlling time. Tests supply a fixed Clock.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}