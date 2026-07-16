package com.schwab.urlShortener.api;

import com.schwab.urlShortener.api.dto.CreateShortLinkRequest;
import com.schwab.urlShortener.api.dto.LinkStatsResponse;
import com.schwab.urlShortener.api.dto.ShortLinkResponse;
import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.repository.ClickEventRepository;
import com.schwab.urlShortener.service.ShortLinkService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

@RestController
@RequestMapping("/api/v1/links")
public class ShortLinkController {

    private final ShortLinkService service;
    private final ClickEventRepository clickEvents;
    private final Clock clock;
    private final String baseUrl;
    private final String redirectPath;

    public ShortLinkController(ShortLinkService service,
                               ClickEventRepository clickEvents,
                               Clock clock,
                               @Value("${app.base-url}") String baseUrl,
                               @Value("${app.redirect-path}") String redirectPath) {
        this.service = service;
        this.clickEvents = clickEvents;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.redirectPath = redirectPath;
    }

    @PostMapping
    public ResponseEntity<ShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request) {
        ShortLink link = service.create(request.url(), request.expiresAt());
        ShortLinkResponse body = ShortLinkResponse.from(link, baseUrl, redirectPath);

        // 201 + Location header: the REST-correct response for resource creation.
        return ResponseEntity
                .created(URI.create(body.shortUrl()))
                .body(body);
    }

    @GetMapping("/{shortCode}")
    public ShortLinkResponse get(@PathVariable String shortCode) {
        return ShortLinkResponse.from(service.resolve(shortCode), baseUrl, redirectPath);
    }

    /**
     * Reads from click_event, which is written asynchronously. Counts may lag
     * a redirect by a few milliseconds - that is the cost of never blocking the
     * redirect on an analytics write.
     */
    @GetMapping("/{shortCode}/stats")
    public LinkStatsResponse stats(@PathVariable String shortCode) {
        // Resolve first: an unknown code returns 404 rather than zeroes, which
        // would wrongly imply the link exists but has no clicks.
        service.resolve(shortCode);

        return new LinkStatsResponse(
                shortCode,
                clickEvents.countByShortCode(shortCode),
                clickEvents.countByShortCodeSince(shortCode,
                        clock.instant().minus(24, ChronoUnit.HOURS)),
                clickEvents.countUniqueVisitors(shortCode));
    }
}