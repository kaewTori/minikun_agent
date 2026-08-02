package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.search.SearchDecisionClientException;
import com.minikun.search.model.SearchDecisionPrompt;
import com.minikun.search.model.SearchDecisionReason;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SearchDecisionClientTest {
    private static final SearchDecisionPrompt PROMPT = new SearchDecisionPrompt(
            "Classify search need. Return JSON only.", "2026-08-02", "latest Java");

    @Test
    void mapsSemanticPromptToLlamaCppRequestAndBuildsDecision() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://flip3.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSearchDecisionClient client = new HttpSearchDecisionClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://flip3.test"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json(expectedRequest(), true))
                .andRespond(withSuccess(llamaResponse(
                        "{\"shouldSearch\":true,\"reason\":\"CURRENT_INFORMATION\"}"),
                        MediaType.APPLICATION_JSON));

        var decision = client.classify(PROMPT);

        assertTrue(decision.shouldSearch());
        assertEquals("latest Java", decision.query());
        assertEquals(SearchDecisionReason.CURRENT_INFORMATION, decision.reason());
        server.verify();
    }

    @Test
    void acceptsValidNonSearchDecision() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://flip3.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSearchDecisionClient client = new HttpSearchDecisionClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://flip3.test"))
                .andRespond(withSuccess(llamaResponse(
                        "{\"shouldSearch\":false,\"reason\":\"GENERAL_KNOWLEDGE\"}"),
                        MediaType.APPLICATION_JSON));

        var decision = client.classify(PROMPT);

        assertTrue(!decision.shouldSearch());
        assertEquals(SearchDecisionReason.GENERAL_KNOWLEDGE, decision.reason());
        server.verify();
    }

    @Test
    void rejectsClosedSchemaViolationsBeforeConstructingDecision() throws Exception {
        assertRejects("{\"shouldSearch\":true,\"reason\":\"RULE_FALLBACK\"}");
        assertRejects("{\"shouldSearch\":true,\"reason\":\"GENERAL_KNOWLEDGE\",\"extra\":1}");
        assertRejects("{\"shouldSearch\":null,\"reason\":\"GENERAL_KNOWLEDGE\"}");
        assertRejects("{\"shouldSearch\":\"true\",\"reason\":\"GENERAL_KNOWLEDGE\"}");
        assertRejects("{\"shouldSearch\":true}");
        assertRejects("{\"shouldSearch\":true,\"reason\":\"NOT_A_REASON\"}");
        assertRejects("not-json");
    }

    @Test
    void reportsHttpFailureWithoutFallback() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://flip3.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSearchDecisionClient client = new HttpSearchDecisionClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://flip3.test"))
                .andRespond(withServerError());

        assertThrows(SearchDecisionClientException.class, () -> client.classify(PROMPT));
        server.verify();
    }

    private static void assertRejects(String response) throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://flip3.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpSearchDecisionClient client = new HttpSearchDecisionClient(builder.build(), new ObjectMapper());
        server.expect(requestTo("http://flip3.test"))
                .andRespond(withSuccess(llamaResponse(response), MediaType.APPLICATION_JSON));

        assertThrows(SearchDecisionClientException.class, () -> client.classify(PROMPT));
        server.verify();
    }

    private static String llamaResponse(String decisionJson) throws Exception {
        return "{\"choices\":[{\"message\":{\"content\":"
                + new ObjectMapper().writeValueAsString(decisionJson) + "}}]}";
    }

    private static String expectedRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return "{\"messages\":[{\"role\":\"system\",\"content\":"
                + mapper.writeValueAsString(PROMPT.instructions())
                + "},{\"role\":\"user\",\"content\":"
                + mapper.writeValueAsString("/no_think\ncurrentDate: 2026-08-02\nuserMessage: latest Java")
                + "}],\"stream\":false,\"max_tokens\":256,\"temperature\":0.0,"
                + "\"response_format\":{\"type\":\"json_schema\",\"json_schema\":{"
                + "\"name\":\"search_decision\",\"schema\":{"
                + "\"type\":\"object\",\"properties\":{"
                + "\"shouldSearch\":{\"type\":\"boolean\"},\"reason\":{\"type\":\"string\"}},"
                + "\"required\":[\"shouldSearch\",\"reason\"],\"additionalProperties\":false},"
                + "\"strict\":true}},\"reasoning_format\":\"none\","
                + "\"chat_template_kwargs\":{\"enable_thinking\":false}}";
    }
}
