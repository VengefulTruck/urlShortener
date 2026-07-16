package com.schwab.urlShortener.api;

import com.schwab.urlShortener.analytics.ClickRecordedEvent;
import com.schwab.urlShortener.analytics.ClientIpHasher;
import com.schwab.urlShortener.service.ShortLinkNotFoundException;
import com.schwab.urlShortener.service.ShortLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RedirectController.class)
@Import(RedirectControllerTest.TestClockConfig.class)
@TestPropertySource(properties = "app.redirect-path=/r")
/*
 * Records events from the real publisher rather than mocking it.
 *
 * @MockitoBean on ApplicationEventPublisher does not take effect: the
 * ApplicationContext itself implements that interface and is registered as a
 * resolvable dependency, so the controller receives the context and the mock
 * sees nothing. The failure is silent - the mock reports zero interactions
 * while the production code works correctly.
 */
@RecordApplicationEvents
class RedirectControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationEvents applicationEvents;

    @MockitoBean private ShortLinkService service;
    @MockitoBean private ClientIpHasher ipHasher;

    private List<ClickRecordedEvent> clickEvents() {
        return applicationEvents.stream(ClickRecordedEvent.class).toList();
    }

    // ---------- the redirect contract ----------

    @Test
    @DisplayName("returns 302 with the target in Location")
    void returns302() throws Exception {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com");

        mockMvc.perform(get("/r/abc1234"))
                .andExpect(status().isFound())            // 302
                .andExpect(header().string(HttpHeaders.LOCATION, "https://example.com"));
    }

    /**
     * The 302-not-301 decision, pinned.
     *
     * 301 is cached by browsers indefinitely: analytics stop after the first
     * click and the link can never be revoked. 301 is the intuitive choice,
     * which is exactly why this test exists - it fails if someone "corrects" it.
     */
    @Test
    @DisplayName("does NOT return 301 permanent redirect")
    void doesNotReturn301() throws Exception {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com");

        mockMvc.perform(get("/r/abc1234"))
                .andExpect(status().is(302));
    }

    @Test
    @DisplayName("sets Cache-Control: no-store so intermediaries cannot cache the hop")
    void setsNoStore() throws Exception {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com");

        mockMvc.perform(get("/r/abc1234"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
    }

    @Test
    @DisplayName("returns an empty body")
    void returnsEmptyBody() throws Exception {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com");

        mockMvc.perform(get("/r/abc1234"))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("unknown code returns 404 problem+json")
    void unknownReturns404() throws Exception {
        when(service.resolveTarget("nope123")).thenThrow(new ShortLinkNotFoundException("nope123"));

        mockMvc.perform(get("/r/nope123"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ---------- analytics ----------

    @Test
    @DisplayName("publishes a click event carrying the request metadata")
    void publishesClickEvent() throws Exception {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com");
        when(ipHasher.hash(any())).thenReturn("hashed-ip");

        mockMvc.perform(get("/r/abc1234")
                .header(HttpHeaders.REFERER, "https://google.com")
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0"));

        assertThat(clickEvents()).singleElement().satisfies(event -> {
            assertThat(event.shortCode()).isEqualTo("abc1234");
            assertThat(event.clickedAt()).isEqualTo(NOW);
            assertThat(event.referrer()).isEqualTo("https://google.com");
            assertThat(event.userAgent()).isEqualTo("Mozilla/5.0");
            assertThat(event.clientIpHash()).isEqualTo("hashed-ip");
        });
    }

    /**
     * The raw IP must never reach the event. Only the hash.
     */
    @Test
    @DisplayName("publishes the hashed IP, never the raw address")
    void publishesHashedIpOnly() throws Exception {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com");
        when(ipHasher.hash(any())).thenReturn("hashed-ip");

        mockMvc.perform(get("/r/abc1234"));

        assertThat(clickEvents()).singleElement().satisfies(event ->
                assertThat(event.clientIpHash())
                        .isEqualTo("hashed-ip")
                        .doesNotContain("127.0.0.1"));
    }

    @Test
    @DisplayName("tolerates missing Referer and User-Agent headers")
    void toleratesMissingHeaders() throws Exception {
        when(service.resolveTarget("abc1234")).thenReturn("https://example.com");

        mockMvc.perform(get("/r/abc1234"))
                .andExpect(status().isFound());

        assertThat(clickEvents()).singleElement().satisfies(event -> {
            assertThat(event.referrer()).isNull();
            assertThat(event.userAgent()).isNull();
        });
    }

    /**
     * A click on a non-existent code must not be recorded. resolveTarget throws
     * before publishEvent runs - if that order is ever reversed, this fails.
     */
    @Test
    @DisplayName("does not publish a click event for an unknown code")
    void doesNotPublishForUnknownCode() throws Exception {
        when(service.resolveTarget("nope123")).thenThrow(new ShortLinkNotFoundException("nope123"));

        mockMvc.perform(get("/r/nope123"));

        assertThat(clickEvents()).isEmpty();
    }
}