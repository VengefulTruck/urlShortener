package com.schwab.urlShortener.api;

import com.schwab.urlShortener.service.ShortLinkService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.redirect-path}")
public class RedirectController {

    private final ShortLinkService service;

    public RedirectController(ShortLinkService service) {
        this.service = service;
    }

    /**
     * Returns 302, not 301.
     *
     * 301 is cached by browsers indefinitely: the client stops contacting us,
     * so analytics stop after the first click and the link can never be revoked
     * or repointed. 302 costs a round trip per click — recovered by the cache in
     * ShortLinkLookup — and keeps both control and measurement.
     */
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String target = service.resolveTarget(shortCode);

        return ResponseEntity
                .status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }
}