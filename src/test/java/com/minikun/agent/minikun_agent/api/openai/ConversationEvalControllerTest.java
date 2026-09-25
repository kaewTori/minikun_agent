package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ConversationEvalControllerTest {
    @Test
    void has125ReviewableThaiScenariosAndRequiresAnExplicitToken() throws Exception {
        var controller = new ConversationEvalController(mock(ChatService.class), new ObjectMapper(), "token");
        var suite = controller.scenarios("token");
        assertEquals(125, suite.size());
        assertEquals(125, suite.stream().map(ConversationEvalController.Scenario::id).distinct().count());
        assertEquals(7, suite.stream().filter(row -> row.id().equals("thai-summary-lag-recall"))
                .findFirst().orElseThrow().turns().size());
        assertEquals(10, suite.stream().filter(row -> row.id().equals("thai-long-window-recall"))
                .findFirst().orElseThrow().turns().size());
        assertTrue(suite.stream().allMatch(row -> !row.turns().isEmpty() && !row.rubric().isBlank()));
        assertThrows(ResponseStatusException.class, () -> controller.evaluate(suite.getFirst().id(), "wrong"));
        assertThrows(ResponseStatusException.class,
                () -> new ConversationEvalController(mock(ChatService.class), new ObjectMapper(), "").scenarios(null));
    }

    @Test
    void runsMultipleTurnsInAnIsolatedOwnerAndChecksActualAnswer() throws Exception {
        var chat = mock(ChatService.class);
        when(chat.chatCompletion(any(), any())).thenReturn(new ChatCompletionResponse("eval", "chat.completion", 1,
                "mini-kun", List.of(new ChatCompletionResponse.Choice(0, new Message("assistant", "มะลิ"), "stop")),
                new ChatCompletionResponse.Usage(1, 1, 2)));
        var controller = new ConversationEvalController(chat, new ObjectMapper(), "token");
        var result = controller.evaluate("thai-cat-recall", "token");
        assertTrue(result.passed());
        assertTrue(result.conversationId().startsWith("eval-"));
        assertEquals(List.of("eval", "eval"), result.responseIds());
        var requests = org.mockito.ArgumentCaptor.forClass(com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest.class);
        verify(chat, times(2)).chatCompletion(requests.capture(), any());
        assertTrue(requests.getValue().owner_id().startsWith("eval-"));
        assertEquals(requests.getAllValues().getFirst().owner_id(), requests.getValue().owner_id());
        assertEquals(3, requests.getValue().messages().size());
    }

    @Test
    void answerChecksDoNotTreatMissingEvidenceOrAttemptedWritesAsSuccess() {
        var scenario = new ConversationEvalController.Scenario("evidence", List.of("ค้นข้อมูล"),
                List.of("Java"), List.of("ส่งแล้ว"), List.of(), true, 100, "ตรวจต้นฉบับ");
        assertFalse(ConversationEvalController.score(scenario, "Java", false, 1).passed());
        assertFalse(ConversationEvalController.score(scenario, "Java [source](https://example.com)", true, 1).passed());
        assertTrue(ConversationEvalController.score(scenario, "Java [source](https://example.com)", false, 1).passed());
    }
}
