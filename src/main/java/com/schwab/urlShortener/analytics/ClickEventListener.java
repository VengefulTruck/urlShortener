package com.schwab.urlShortener.analytics;

import com.schwab.urlShortener.domain.ClickEvent;
import com.schwab.urlShortener.repository.ClickEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listens for clicks and writes them to the database on a background thread.
 *
 * Its own class, not a method on the controller: @Async works through a Spring
 * proxy. A call inside the same class would bypass the proxy and run on the
 * redirect thread instead — with no error to tell you.
 *
 * Catches its own exceptions: the user's redirect already completed. A failed
 * analytics write must never become their problem. Losing a click is
 * acceptable; a slow or broken redirect is not.
 */
@Component
public class ClickEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClickEventListener.class);

    private final ClickEventRepository repository;

    public ClickEventListener(ClickEventRepository repository) {
        this.repository = repository;
    }

    @Async("analyticsExecutor")
    @EventListener
    @Transactional
    public void onClick(ClickRecordedEvent event) {
        try {
            repository.save(new ClickEvent(
                    event.shortCode(),
                    event.clickedAt(),
                    event.referrer(),
                    event.userAgent(),
                    event.clientIpHash()));
        } catch (Exception e) {
            log.error("Failed to record click for {}", event.shortCode(), e);
        }
    }
}