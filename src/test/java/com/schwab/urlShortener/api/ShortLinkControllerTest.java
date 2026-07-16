package com.schwab.urlShortener.api;

import com.schwab.urlShortener.domain.ShortLink;
import com.schwab.urlShortener.repository.ClickEventRepository;
import com.schwab.urlShortener.service.InvalidUrlException;
import com.schwab.urlShortener.service.ShortLinkNotFoundException;
import com.schwab.urlShortener.service.ShortLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShortLinkController.class)
@Import(ShortLinkControllerTest.TestClockConfig.class)
@TestPropertySource(properties = {
        "app.base-url=http://localhost:8080",
        "app.redirect-path=/r"
})
class ShortLinkControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ShortLinkService service;
    @MockitoBean private ClickEventRepository clickEvents;

    // ---------- create ----------

    @Test
    @DisplayName("POST returns 201 with a Location header and the full body")
    void createReturns201() throws Exception {
        when(service.create(eq("https://example.com"), any()))
                .thenReturn(new ShortLink("abc1234", "https://example.com", NOW, null));

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                // 201 must carry Location: this is what makes it REST-correct
                .andExpect(header().string("Location", "http://localhost:8080/r/abc1234"))
                .andExpect(jsonPath("$.shortCode").value("abc1234"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/r/abc1234"))
                .andExpect(jsonPath("$.longUrl").value("https://example.com"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
    }

    /**
     * The response must never expose the entity's internals. If someone swaps
     * the DTO for the entity, these fail.
     */
    @Test
    @DisplayName("POST response leaks no internal fields")
    void createLeaksNoInternals() throws Exception {
        when(service.create(any(), any()))
                .thenReturn(new ShortLink("abc1234", "https://example.com", NOW, null));

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.active").doesNotExist());
    }

    // ---------- validation at the boundary ----------

    @Test
    @DisplayName("POST with a blank url returns 400 problem+json")
    void blankUrlReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Validation failed"));
    }

    @Test
    @DisplayName("POST with no url field returns 400")
    void missingUrlReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST with malformed JSON returns 400, not 500")
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    /**
     * A rejected scheme is the caller's fault: 400, not 500. A 500 here would
     * page an on-call engineer every time someone submits a bad URL.
     */
    @Test
    @DisplayName("POST with a rejected scheme returns 400 and echoes the reason")
    void rejectedSchemeReturns400() throws Exception {
        when(service.create(any(), any()))
                .thenThrow(new InvalidUrlException("Scheme 'javascript' is not allowed; use http or https"));

        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid URL"))
                .andExpect(jsonPath("$.detail").value("Scheme 'javascript' is not allowed; use http or https"));
    }

    // ---------- read ----------

    @Test
    @DisplayName("GET returns link metadata")
    void getReturnsMetadata() throws Exception {
        when(service.resolve("abc1234"))
                .thenReturn(new ShortLink("abc1234", "https://example.com", NOW, null));

        mockMvc.perform(get("/api/v1/links/abc1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc1234"));
    }

    @Test
    @DisplayName("GET on an unknown code returns 404, not 500")
    void getUnknownReturns404() throws Exception {
        when(service.resolve("nope123")).thenThrow(new ShortLinkNotFoundException("nope123"));

        mockMvc.perform(get("/api/v1/links/nope123"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Link not found"));
    }

    /**
     * The 404 body must not confirm the code ever existed - that would help
     * someone enumerating codes.
     */
    @Test
    @DisplayName("404 body does not echo the requested code")
    void notFoundDoesNotEchoCode() throws Exception {
        when(service.resolve("secret1")).thenThrow(new ShortLinkNotFoundException("secret1"));

        mockMvc.perform(get("/api/v1/links/secret1"))
                .andExpect(jsonPath("$.detail").value("No active link exists for that code."));
    }

    // ---------- stats ----------

    @Test
    @DisplayName("GET stats returns click counts")
    void statsReturnsCounts() throws Exception {
        when(service.resolve("abc1234"))
                .thenReturn(new ShortLink("abc1234", "https://example.com", NOW, null));
        when(clickEvents.countByShortCode("abc1234")).thenReturn(42L);
        when(clickEvents.countByShortCodeSince(eq("abc1234"), any())).thenReturn(7L);
        when(clickEvents.countUniqueVisitors("abc1234")).thenReturn(3L);

        mockMvc.perform(get("/api/v1/links/abc1234/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(42))
                .andExpect(jsonPath("$.clicksLast24h").value(7))
                .andExpect(jsonPath("$.uniqueVisitors").value(3));
    }

    /**
     * An unknown code must 404 rather than return zeroes, which would wrongly
     * imply the link exists but has no clicks.
     */
    @Test
    @DisplayName("GET stats on an unknown code returns 404, not zeroes")
    void statsUnknownReturns404() throws Exception {
        when(service.resolve("nope123")).thenThrow(new ShortLinkNotFoundException("nope123"));

        mockMvc.perform(get("/api/v1/links/nope123/stats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.totalClicks").doesNotExist());
    }
}