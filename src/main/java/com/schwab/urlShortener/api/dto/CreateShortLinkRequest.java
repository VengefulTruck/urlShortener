package com.schwab.urlShortener.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CreateShortLinkRequest(

        @NotBlank(message = "url is required")
        @Size(max = 2048, message = "url must not exceed 2048 characters")
        String url,

        Instant expiresAt
) {}