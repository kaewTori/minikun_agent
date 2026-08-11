package com.minikun.model.existing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.minikun.model.ChatModelId;

import reactor.core.publisher.Flux;

class ExistingChatModelProviderTest {
    @Test
    void delegatesChatToExistingModel() {
        ChatModel chatModel = mock(ChatModel.class);
        Prompt prompt = new Prompt("Hello");
        ChatResponse response = response("Hi");
        when(chatModel.call(prompt)).thenReturn(response);
        ExistingChatModelProvider provider = new ExistingChatModelProvider(chatModel);

        assertEquals(response, provider.chat(prompt));
        verify(chatModel).call(prompt);
    }

    @Test
    void delegatesStreamToExistingModel() {
        ChatModel chatModel = mock(ChatModel.class);
        Prompt prompt = new Prompt("Hello");
        Flux<ChatResponse> responses = Flux.just(response("Hi"));
        when(chatModel.stream(prompt)).thenReturn(responses);
        ExistingChatModelProvider provider = new ExistingChatModelProvider(chatModel);

        assertEquals(List.of("Hi"), provider.stream(prompt)
                .map(chatResponse -> chatResponse.getResult().getOutput().getText())
                .collectList()
                .block());
        verify(chatModel).stream(prompt);
    }

    @Test
    void exposesExistingIdAndCapabilities() {
        ExistingChatModelProvider provider = new ExistingChatModelProvider(mock(ChatModel.class));

        assertEquals(ChatModelId.EXISTING, provider.id());
        assertEquals(true, provider.capabilities().streaming());
        assertEquals(true, provider.capabilities().toolCalling());
        assertEquals(false, provider.capabilities().vision());
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}