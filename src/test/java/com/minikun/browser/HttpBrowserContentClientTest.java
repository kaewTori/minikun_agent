package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpBrowserContentClientTest {
    private static final String WORKER_URL = "http://browser-worker.test";
    private static final String PAGE_URL = "https://example.com/watch";

    @Test
    void retriesAnInvalidResponseThenReturnsRenderedContent() {
        RestClient.Builder builder = RestClient.builder().baseUrl(WORKER_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpBrowserContentClient client = new HttpBrowserContentClient(builder.build(), "");
        server.expect(requestTo(WORKER_URL + "/render"))
                .andRespond(withSuccess(
                        "{\"url\":\"https://example.com/watch\",\"content\":null,"
                                + "\"content_type\":\"text/html; rendered\",\"truncated\":false}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(WORKER_URL + "/render"))
                .andRespond(withSuccess(
                        "{\"url\":\"https://example.com/watch\",\"content\":\"page\","
                                + "\"content_type\":\"text/html; rendered\",\"truncated\":false}",
                        MediaType.APPLICATION_JSON));

        BrowserContent content = client.render(PAGE_URL);

        assertEquals("page", content.content());
        server.verify();
    }

    @Test
    void retriesServerErrorThenReportsHttpFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl(WORKER_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpBrowserContentClient client = new HttpBrowserContentClient(builder.build(), "");
        server.expect(requestTo(WORKER_URL + "/render")).andRespond(withServerError());
        server.expect(requestTo(WORKER_URL + "/render")).andRespond(withServerError());

        BrowserContentException exception = assertThrows(
                BrowserContentException.class, () -> client.render(PAGE_URL));

        assertEquals("browser worker returned HTTP 500", exception.getMessage());
        server.verify();
    }
}