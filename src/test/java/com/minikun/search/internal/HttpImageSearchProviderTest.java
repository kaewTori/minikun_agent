package com.minikun.search.internal;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.SearchProviderUnavailableException;
import com.minikun.search.model.ImageSearchRequest;

class HttpImageSearchProviderTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void sendsMultipartImageAndMapsProviderResults() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpImageSearchProvider provider = new HttpImageSearchProvider(
                builder.build(), new ObjectMapper(), CLOCK, "http://image.test/search", true);
        server.expect(requestTo("http://image.test/search"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(containsString("name=\"image\"")))
                .andRespond(withSuccess("""
                        {"results":[
                          {"image_url":"https://images.example/a.png","title":"Match",
                           "source_url":"https://example.com/page","description":"same subject",
                           "thumbnail_url":"https://images.example/thumb.png","provider":"lens"},
                          {"title":"Malformed"}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        var response = provider.search(request());

        assertEquals(1, response.images().size());
        assertEquals("https://images.example/a.png", response.images().getFirst().url());
        assertEquals("https://example.com/page", response.images().getFirst().sourceUrl());
        assertEquals("lens", response.images().getFirst().provider());
        server.verify();
    }

    @Test
    void failsClosedWhenProviderIsNotConfigured() {
        HttpImageSearchProvider provider = new HttpImageSearchProvider(
                RestClient.builder().build(), new ObjectMapper(), CLOCK, "", false);

        assertThrows(SearchProviderUnavailableException.class, () -> provider.search(request()));
    }

    private ImageSearchRequest request() {
        return new ImageSearchRequest(
                UUID.randomUUID(), new byte[] {1, 2, 3}, "image/png", 5, CLOCK.instant().plusSeconds(60));
    }
}
