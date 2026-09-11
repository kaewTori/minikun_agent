package com.minikun.agent.minikun_agent.api.openai;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.GenerationOptions;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KimiK3ReasoningClientTest {
    @Test
    void sendsPrivateReasoningRequestAndReturnsHandoff() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://nvidia.test/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KimiK3ReasoningClient client = new KimiK3ReasoningClient(
                builder.build(), new ObjectMapper(), "nvapi-test", "moonshotai/kimi-k3");

        server.expect(requestTo("https://nvidia.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer nvapi-test"))
                .andExpect(content().string(containsString("\"reasoning_effort\":\"high\"")))
                .andExpect(content().string(containsString("solve")))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"role":"assistant","content":"Use the verified facts."}}]}
                        """, MediaType.APPLICATION_JSON));

        assertEquals("Use the verified facts.", client.handoff(
                new org.springframework.ai.chat.prompt.Prompt("solve"),
                GenerationOptions.Reasoning.MEDIUM));
        server.verify();
    }

    @Test
    void rejectsReasoningWhenKeyIsMissing() {
        KimiK3ReasoningClient client = new KimiK3ReasoningClient(
                RestClient.create(), new ObjectMapper(), "", "moonshotai/kimi-k3");

        assertThrows(IllegalStateException.class, () -> client.handoff(
                new org.springframework.ai.chat.prompt.Prompt("solve"),
                GenerationOptions.Reasoning.HIGH));
    }
}
