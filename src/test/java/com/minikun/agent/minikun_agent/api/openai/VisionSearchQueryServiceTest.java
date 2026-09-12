package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.vision.VisionInput;

class VisionSearchQueryServiceTest {
    @Test
    void rejectsAgenticToolMarkupInsteadOfUsingItAsSearchQuery() {
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        when(provider.chat(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("<|tool*call>call:GoogleSearch{queries:[...]}<|tool*call|>")))));

        Media media = new Media(MimeTypeUtils.parseMimeType("image/png"), new ByteArrayResource(new byte[] {1}));
        VisionInput input = new VisionInput(
                List.of(media), List.of(new VisionInput.Image("image/png", new byte[] {1, 2, 3})));
        ChatModelGateway gateway = new ChatModelGateway(active, null, null, false);

        VisionSearchQueryService.Result result = new VisionSearchQueryService().resolve(
                true, "find similar images using this image", input, gateway, "vision-model",
                "request", new ConversationId("vision-query"), ChatRequestContext.direct());

        assertEquals("", result.query());
        assertEquals(List.of(), result.alternateQueries());
    }

    @Test
    void buildsSearchQueriesFromAConservativeVisualProfile() {
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        when(provider.chat(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                new AssistantMessage("""
                        {"subject":"black leather shoulder bag","attributes":["quilted","gold chain strap"],
                         "colors":["black","gold"],"style":["product photography"],
                         "setting":["plain background"],"composition":["centered close-up"],"mood":[],
                         "uncertain":["designer brand"]}
                        """)))));

        Media media = new Media(MimeTypeUtils.parseMimeType("image/png"), new ByteArrayResource(new byte[] {1}));
        VisionInput input = new VisionInput(
                List.of(media), List.of(new VisionInput.Image("image/png", new byte[] {1, 2, 3})));
        ChatModelGateway gateway = new ChatModelGateway(active, null, null, false);

        VisionSearchQueryService.Result result = new VisionSearchQueryService().resolve(
                true, "find similar images using this image", input, gateway, "vision-model",
                "request", new ConversationId("vision-profile"), ChatRequestContext.direct());

        assertEquals("black leather shoulder bag quilted gold chain strap black gold product photography "
                + "plain background centered close-up", result.query());
        assertEquals(List.of(
                "black leather shoulder bag quilted gold chain strap black gold",
                "black leather shoulder bag product photography plain background centered close-up"),
                result.alternateQueries());
        org.mockito.Mockito.verify(provider).chat(any(Prompt.class));
    }
}
