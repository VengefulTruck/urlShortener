package com.schwab.urlShortener.api;

import com.schwab.urlShortener.api.dto.CreateShortLinkRequest;
import com.schwab.urlShortener.api.dto.ShortLinkResponse;
import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.service.ShortLinkService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/links")
public class ShortLinkController {

    private final ShortLinkService service;
    private final String baseUrl;
    private final String redirectPath;

    public ShortLinkController(ShortLinkService service,
                               @Value("${app.base-url}") String baseUrl,
                               @Value("${app.redirect-path}") String redirectPath) {
        this.service = service;
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
}