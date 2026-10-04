package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class Crawl4AiBrowserContentClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://crawl4ai.test");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final Crawl4AiBrowserContentClient client = new Crawl4AiBrowserContentClient(builder.build(), "secret",
            Duration.ofSeconds(15), Duration.ofMillis(1500), Duration.ZERO);
    private static final String URL = "https://example.com/article";

    @Test
    void requestsBoundedDynamicRenderingAndGetsBothMarkdownVariantsInOneRequest() throws Exception {
        String fit = "Useful article. ".repeat(20);
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer secret"))
                .andExpect(content().json("""
                        {"urls":["https://example.com/article"],
                         "crawler_config":{"type":"CrawlerRunConfig","params":{
                         "wait_until":"domcontentloaded","wait_for":"css:body","wait_for_timeout":3000,
                         "page_timeout":15000,"delay_before_return_html":1.5,"scan_full_page":true,
                         "max_scroll_steps":8,"cache_mode":{"type":"CacheMode","params":"bypass"}}}}
                        """))
                .andRespond(withSuccess(page(200, fit, fit + " Extra table."), MediaType.APPLICATION_JSON));
        BrowserContent result = client.render(URL);
        assertEquals(fit, result.content());
        assertTrue(result.rawContent().endsWith("Extra table."));
        assertEquals("text/markdown", result.contentType());
        server.verify();
    }

    @Test
    void usesRawWhenFilteringLeavesSparseContent() throws Exception {
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andRespond(withSuccess(page(200, "# Product", "# Product\nPrice: 100 THB\nStock: 5"), MediaType.APPLICATION_JSON));
        assertTrue(client.render(URL).content().contains("Price: 100 THB"));
        server.verify();
    }

    @Test
    void rejectsCloudflareHtmlEvenWhenMarkdownIsEmptyAndDoesNotRetry() throws Exception {
        String json = new ObjectMapper().writeValueAsString(Map.of("success", true, "results", java.util.List.of(
                Map.of("success", true, "status_code", 403, "html", "<title>Just a moment...</title><script src='/cdn-cgi/challenge-platform/cf-chl-test'></script>",
                        "markdown", Map.of("raw_markdown", "", "fit_markdown", "")))));
        server.expect(requestTo("http://crawl4ai.test/crawl")).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
        assertTrue(assertThrows(BrowserContentException.class, () -> client.render(URL)).getMessage().contains("verification required"));
        server.verify();
    }

    @Test
    void rejectsCaptchaMarkdownAndDoesNotRetry() throws Exception {
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andRespond(withSuccess(page(200, "", "Verify you are human"), MediaType.APPLICATION_JSON));
        assertTrue(assertThrows(BrowserContentException.class, () -> client.render(URL)).getMessage().contains("CAPTCHA"));
        server.verify();
    }

    @Test
    void retriesTransientServerFailure() throws Exception {
        server.expect(requestTo("http://crawl4ai.test/crawl")).andRespond(withServerError());
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andRespond(withSuccess(page(200, "", "Article content"), MediaType.APPLICATION_JSON));
        assertEquals("Article content", client.render(URL).content());
        server.verify();
    }

    @Test
    void retriesRateLimitedRequestsWithBoundedRetryAfter() throws Exception {
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "0"));
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andRespond(withSuccess(page(200, "", "Article content"), MediaType.APPLICATION_JSON));
        assertEquals("Article content", client.render(URL).content());
        server.verify();
    }

    @Test
    void retriesWebsiteServerErrorButNotWebsiteForbidden() throws Exception {
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andRespond(withSuccess(page(503, "", "Unavailable"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://crawl4ai.test/crawl"))
                .andRespond(withSuccess(page(403, "", "Forbidden"), MediaType.APPLICATION_JSON));
        assertEquals("website returned HTTP 403", assertThrows(BrowserContentException.class, () -> client.render(URL)).getMessage());
        server.verify();
    }

    @Test
    void retainsRetryLimitAndReportsFailure() {
        server.expect(requestTo("http://crawl4ai.test/crawl")).andRespond(withServerError());
        server.expect(requestTo("http://crawl4ai.test/crawl")).andRespond(withServerError());
        assertEquals("Crawl4AI returned HTTP 500", assertThrows(BrowserContentException.class, () -> client.render(URL)).getMessage());
        server.verify();
    }

    @Test
    void doesNotRetryAuthenticationFailures() {
        server.expect(requestTo("http://crawl4ai.test/crawl")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertEquals("Crawl4AI returned HTTP 401", assertThrows(BrowserContentException.class, () -> client.render(URL)).getMessage());
        server.verify();
    }

    private String page(int status, String fit, String raw) throws Exception {
        return new ObjectMapper().writeValueAsString(Map.of("success", true, "results", java.util.List.of(
                Map.of("success", true, "url", URL, "status_code", status,
                        "markdown", Map.of("fit_markdown", fit, "raw_markdown", raw)))));
    }
}
