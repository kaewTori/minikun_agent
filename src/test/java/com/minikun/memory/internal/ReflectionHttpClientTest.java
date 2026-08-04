package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.minikun.memory.MemoryException;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.reflection.ReflectionPrompt;

class ReflectionHttpClientTest {
    private static final ReflectionPrompt PROMPT = new ReflectionPrompt(
            new CompletedConversation("conversation-1",
                    List.of(new CompletedConversation.Message("user", "I use macOS"))),
            "reflection instructions");

    @Test
    void serializesOpenAiRequestWithoutModelAndExtractsRawContent() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://flip3.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionHttpClient client = new ReflectionHttpClient(builder.build());
        server.expect(requestTo("http://flip3.test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(
                        "{\"messages\":[{\"role\":\"user\",\"content\":\"reflection instructions\"}],"
                                + "\"stream\":false,\"max_tokens\":384,\"temperature\":0.0,"
                                + "\"response_format\":{\"type\":\"json_object\"}}", true))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                                + "\"content\":\"not JSON and intentionally opaque\"}}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals("not JSON and intentionally opaque", client.reflect(PROMPT));
        server.verify();
    }

    @Test
    void rejectsEmptyResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://flip3.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionHttpClient client = new ReflectionHttpClient(builder.build());
        server.expect(requestTo("http://flip3.test"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(MemoryException.class, () -> client.reflect(PROMPT));
        server.verify();
    }

    @Test
    void propagatesTransportFailure() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://flip3.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReflectionHttpClient client = new ReflectionHttpClient(builder.build());
        server.expect(requestTo("http://flip3.test")).andRespond(withServerError());

        assertThrows(RuntimeException.class, () -> client.reflect(PROMPT));
        server.verify();
    }
}
