package com.schwab.urlShortener;

import com.schwab.urlShortener.repository.ClickEventRepository;
import com.schwab.urlShortener.repository.ShortLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack test: real Spring context, real H2, real Flyway migrations, real
 * cache, real async executor. Everything below the HTTP layer is genuine.
 *
 * Slow (seconds, not milliseconds) and therefore deliberately few. Unit tests
 * cover the logic; this covers the wiring between it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ShortLinkIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ShortLinkRepository shortLinks;
    @Autowired private ClickEventRepository clickEvents;
    @Autowired private CacheManager cacheManager;

    /**
     * H2 is in-memory but the schema persists across tests in the same context.
     * Clearing both tables and the cache keeps each test independent - otherwise
     * they pass or fail depending on execution order.
     */
    @BeforeEach
    void reset() {
        clickEvents.deleteAll();
        shortLinks.deleteAll();
        cacheManager.getCacheNames()
                .forEach(name -> cacheManager.getCache(name).clear());
    }

    private String createLink(String url) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return com.jayway.jsonpath.JsonPath.read(
                result.getResponse().getContentAsString(), "$.shortCode");
    }

    @Test
    @DisplayName("create then redirect works end to end")
    void createThenRedirect() throws Exception {
        String code = createLink("https://example.com");

        mockMvc.perform(get("/r/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com"));
    }

    @Test
    @DisplayName("the created link is actually persisted")
    void linkIsPersisted() throws Exception {
        String code = createLink("https://example.com");

        assertThat(shortLinks.findByShortCode(code)).isPresent();
    }

    @Test
    @DisplayName("two links for the same URL get different codes")
    void sameUrlGetsDistinctCodes() throws Exception {
        assertThat(createLink("https://example.com"))
                .isNotEqualTo(createLink("https://example.com"));
    }

    /**
     * Proves the security control survives the full stack: the scheme allowlist
     * is enforced end to end, and nothing is written.
     */
    @Test
    @DisplayName("a dangerous scheme is rejected and never persisted")
    void dangerousSchemeNeverPersisted() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"javascript:alert(1)\"}"))
                .andExpect(status().isBadRequest());

        assertThat(shortLinks.count()).isZero();
    }

    /**
     * Analytics are written asynchronously, so the assertion must wait rather
     * than read immediately. This lag is the design, not a defect: the redirect
     * never blocks on the analytics write.
     */
    @Test
    @DisplayName("clicks are recorded asynchronously and reach the stats endpoint")
    void clicksAreRecorded() throws Exception {
        String code = createLink("https://example.com");

        mockMvc.perform(get("/r/" + code)).andExpect(status().isFound());
        mockMvc.perform(get("/r/" + code)).andExpect(status().isFound());
        mockMvc.perform(get("/r/" + code)).andExpect(status().isFound());

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() ->
                        assertThat(clickEvents.countByShortCode(code)).isEqualTo(3));

        mockMvc.perform(get("/api/v1/links/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClicks").value(3))
                .andExpect(jsonPath("$.uniqueVisitors").value(1));
    }

    @Test
    @DisplayName("no click is recorded for an unknown code")
    void unknownCodeRecordsNothing() throws Exception {
        mockMvc.perform(get("/r/nothere")).andExpect(status().isNotFound());

        assertThat(clickEvents.count()).isZero();
    }

    @Test
    @DisplayName("unknown code returns 404 through the full stack")
    void unknownCodeReturns404() throws Exception {
        mockMvc.perform(get("/r/nothere"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    @DisplayName("stats on an unknown code returns 404")
    void statsUnknownReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/links/nothere/stats"))
                .andExpect(status().isNotFound());
    }
}