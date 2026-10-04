package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.commands.CommandCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatRequestInspectorTest {
    private final ChatRequestInspector inspector = new ChatRequestInspector();

    @Test
    void identifiesInternalTitleRequestsAndKeepsOnlyConversationMessages() {
        ChatCompletionRequest request = request(List.of(
                new Message("system", "Generate a concise title summarizing the chat history"),
                new Message("user", "สวัสดี"),
                new Message("assistant", "สวัสดีครับ")));

        assertTrue(inspector.isInternalTitleRequest(request));
        assertFalse(inspector.shouldPersist(request));
        assertEquals(List.of("user", "assistant"),
                inspector.titleMessages(request).stream().map(message -> message.role()).toList());
    }

    @Test
    void selectsTheLastNonBlankUserMessage() {
        List<Message> messages = List.of(
                new Message("user", "first"),
                new Message("assistant", "answer"),
                new Message("user", "latest"));

        assertEquals(2, inspector.lastUserMessageIndex(messages));
    }

    @Test
    void derivesConversationContextWithoutTreatingSlashCommandsAsConversation() {
        var commands = new CommandCatalog();
        var current = request(List.of(new Message("user", "ต่อเลย")));
        var commandHistory = List.of(new ChatMessage("user", "/help"));
        assertFalse(inspector.hasConversationContext(commandHistory, current, commands));
        assertEquals("", inspector.classifierContext(commandHistory, commands));
        assertEquals("", inspector.classifierContext(null, commands));

        var history = List.of(new ChatMessage("system", "internal instructions"),
                new ChatMessage("user", "/help"), new ChatMessage("user", "PostgreSQL"),
                new ChatMessage("assistant", "ฐานข้อมูลเชิงสัมพันธ์"));
        assertTrue(inspector.hasConversationContext(history, current, commands));
        assertEquals("user: PostgreSQL\nassistant: ฐานข้อมูลเชิงสัมพันธ์",
                inspector.classifierContext(history, commands));
        assertTrue(inspector.hasConversationContext(List.of(), request(List.of(
                new Message("user", "PostgreSQL"), new Message("assistant", "ฐานข้อมูล"),
                new Message("user", "ต่อเลย"))), commands));
    }

    private ChatCompletionRequest request(List<Message> messages) {
        return new ChatCompletionRequest("mini-kun", messages, "conversation", false, null, null, null);
    }
}
