package com.minikun.model.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

class OllamaTaskModelProviderTest {
    @Test
    void mapsLocalOpenAiCompatibleRequestAndParsesResponse() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1/chat/completions");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTaskModelProvider provider = new OllamaTaskModelProvider(
                builder.build(), new ObjectMapper(), "qwen3.5:0.6b", Duration.ofSeconds(2));

        server.expect(requestTo("http://ollama.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"model":"qwen3.5:0.6b","messages":[{"role":"user","content":"Name this"}],
                        "stream":false,"max_tokens":32,"temperature":0.0}
                        """, false))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"A title"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertEquals("A title", provider.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("user", "Name this")), 32, 0.0,
                TaskModelRequest.ResponseFormat.TEXT)));
        server.verify();
    }

    @Test
    void rejectsMalformedResponse() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ollama.test/v1/chat/completions");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OllamaTaskModelProvider provider = new OllamaTaskModelProvider(
                builder.build(), new ObjectMapper(), "qwen3.5:0.6b", Duration.ofSeconds(2));
        server.expect(requestTo("http://ollama.test/v1/chat/completions"))
                .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, () -> provider.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("user", "Name this")), 32, 0.0,
                TaskModelRequest.ResponseFormat.TEXT)));
        server.verify();
    }
}