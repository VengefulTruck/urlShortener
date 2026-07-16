package com.schwab.urlShortener.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Configured explicitly. Spring's default is SimpleAsyncTaskExecutor, which
     * creates a brand new thread for every task and never reuses them. Fine at
     * 5 requests. At 1000/sec it makes 1000 threads/sec and the JVM dies.
     */
    @Bean("analyticsExecutor")
    public Executor analyticsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("analytics-");

        // AbortPolicy, NOT CallerRunsPolicy.
        // CallerRuns would hand the database write back to the redirect thread
        // exactly when we are most overloaded - the precise outcome this whole
        // design exists to prevent. Dropping analytics is acceptable.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());

        executor.initialize();
        return executor;
    }

    /**
     * Without this, an exception from an @Async void method goes nowhere.
     * No log, no alert, no trace. It simply vanishes.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
                log.error("Async failure in {}", method.getName(), throwable);
    }
}