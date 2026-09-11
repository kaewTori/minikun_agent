package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class Crawl4AiBrowserContentClientTest {
    private static final String CRAWL4AI_URL = "http://crawl4ai.test";
    private static final String PAGE_URL = "https://example.com/watch";

    @Test
    void retriesAnInvalidResponseThenReturnsFitMarkdown() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CRAWL4AI_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Crawl4AiBrowserContentClient client = new Crawl4AiBrowserContentClient(builder.build(), "secret");
        server.expect(requestTo(CRAWL4AI_URL + "/md"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andExpect(content().json("{\"url\":\"https://example.com/watch\",\"f\":\"fit\",\"c\":\"0\"}"))
                .andRespond(withSuccess(
                        "{\"url\":\"https://example.com/watch\",\"markdown\":null,\"success\":true}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(CRAWL4AI_URL + "/md"))
                .andRespond(withSuccess(
                        "{\"url\":\"https://example.com/watch\",\"markdown\":\"# Page\",\"success\":true}",
                        MediaType.APPLICATION_JSON));

        BrowserContent content = client.render(PAGE_URL);

        assertEquals("# Page", content.content());
        assertEquals("text/markdown", content.contentType());
        server.verify();
    }

    @Test
    void retriesServerErrorThenReportsHttpFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CRAWL4AI_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Crawl4AiBrowserContentClient client = new Crawl4AiBrowserContentClient(builder.build(), "");
        server.expect(requestTo(CRAWL4AI_URL + "/md")).andRespond(withServerError());
        server.expect(requestTo(CRAWL4AI_URL + "/md")).andRespond(withServerError());

        BrowserContentException exception = assertThrows(
                BrowserContentException.class, () -> client.render(PAGE_URL));

        assertEquals("Crawl4AI returned HTTP 500", exception.getMessage());
        server.verify();
    }
}
