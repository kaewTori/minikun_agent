package com.minikun.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class PresentationAttachmentScopeTest {
    @Test
    void carriesOnlyVerifiedToolAttachmentsAcrossFinalModelResponse() {
        ChatAttachment attachment = new ChatAttachment("presentation", "/download", "Deck", "", "", "generated",
                "/download", "", null, null, "", "", "", "", null, "artifact-1",
                "deck.pptx", PresentationStore.CONTENT_TYPE, 12L, 2, "artifact-1");
        ChatResponse draft = new ChatResponse(List.of(new Generation(new AssistantMessage("draft"))));
        ChatResponse finalResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("answer"))));

        ChatResponse withAttachment;
        try (var scope = PresentationAttachmentScope.open()) {
            PresentationAttachmentScope.add(attachment);
            withAttachment = scope.attach(draft);
        }
        ChatResponse carried = PresentationAttachmentScope.carry(finalResponse, withAttachment);

        assertEquals("draft", withAttachment.getResult().getOutput().getText());
        assertEquals("answer", carried.getResult().getOutput().getText());
        assertEquals(List.of(attachment), PresentationAttachmentScope.attachments(carried));
    }
}
