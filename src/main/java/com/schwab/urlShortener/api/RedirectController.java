package com.schwab.urlShortener.api;

import com.schwab.urlShortener.analytics.ClickRecordedEvent;
import com.schwab.urlShortener.analytics.ClientIpHasher;
import com.schwab.urlShortener.service.ShortLinkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@RestController
@RequestMapping("${app.redirect-path}")
public class RedirectController {

    private final ShortLinkService service;
    private final ApplicationEventPublisher events;
    private final ClientIpHasher ipHasher;
    private final Clock clock;

    public RedirectController(ShortLinkService service,
                              ApplicationEventPublisher events,
                              ClientIpHasher ipHasher,
                              Clock clock) {
        this.service = service;
        this.events = events;
        this.ipHasher = ipHasher;
        this.clock = clock;
    }

    /**
     * 302, not 301: browsers cache a 301 forever, which kills analytics after
     * the first click and makes a link impossible to revoke. The per-click
     * round trip is paid for by the cache in ShortLinkLookup.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode,
                                         HttpServletRequest request) {
        String target = service.resolveTarget(shortCode);

        // Announce, don't write. The listener is @Async, so this call returns
        // immediately and the database insert never touches this thread.
        events.publishEvent(new ClickRecordedEvent(
                shortCode,
                clock.instant(),
                request.getHeader(HttpHeaders.REFERER),
                request.getHeader(HttpHeaders.USER_AGENT),
                ipHasher.hash(request)));

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}