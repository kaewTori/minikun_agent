package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.vision.VisionInput;
import com.minikun.visual.GeneratedImage;
import com.minikun.visual.GeneratedImageStore;
import com.minikun.visual.ImageGenerationRequest;
import com.minikun.visual.ImageGenerationTool;
import com.minikun.visual.PonyPromptTransformer;
import com.minikun.visual.StoryIllustrationProvider;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

class PonyPromptChatServiceTest {
    @TempDir Path directory;

    @Test
    void acceptsBroadImageRequestsAndWaitsForAnExplicitGenerationFollowup() {
        PonyPromptChatService service = service(brief -> "1girl, red dress", new AtomicReference<>());
        VisionInput image = image();
        for (String text : List.of(
                "แปลงรูปนี้เป็น Pony prompt ให้หน่อย",
                "เปลี่ยนภาพนี้เป็นเวอร์ชันอนิเมะ แสงเย็น เสื้อแดง",
                "อยากได้แบบอนิเมะจากรูปที่ส่งไป",
                "make this photo a watercolor version")) {
            assertEquals(PonyPromptChatService.Action.DRAFT,
                    service.action(request(List.of(new Message("user", text))), text, image));
        }
        assertEquals(PonyPromptChatService.Action.NONE,
                service.action(request(List.of(new Message("user", "รูปนี้คืออะไร"))),
                        "รูปนี้คืออะไร", image));
        assertEquals(PonyPromptChatService.Action.NONE,
                service.action(request(List.of(new Message("user", "ทำไมภาพนี้สีเพี้ยน"))),
                        "ทำไมภาพนี้สีเพี้ยน", image));
        assertEquals(PonyPromptChatService.Action.NONE,
                service.action(request(List.of(new Message("user", "แก้ปฏิทิน"))),
                        "แก้ปฏิทิน\n[ภาพจากข้อความก่อนหน้า: รูป.png]", image));
    }

    @Test
    void classifiesAnIndirectVisualRequestWithoutRestrictingItsWording() {
        PonyPromptChatService service = service(brief -> "1girl", new AtomicReference<>());
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        when(provider.chat(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("DRAFT")))));

        assertEquals(PonyPromptChatService.Action.DRAFT, service.classify(
                "รูปนี้ช่วยให้เป็นแฟนตาซีหน่อย", new ChatModelGateway(active, null, null, false),
                "vision-model", "req", new ConversationId("pony-chat")));
    }

    @Test
    void showsThePreparedPromptBeforeGeneratingThatSamePrompt() {
        AtomicReference<ImageGenerationRequest> generated = new AtomicReference<>();
        AtomicReference<String> transformedBrief = new AtomicReference<>();
        PonyPromptChatService service = service(brief -> {
            transformedBrief.set(brief);
            return "1girl, red dress, moonlight";
        }, generated);
        ActiveChatModelProvider active = mock(ActiveChatModelProvider.class);
        ChatModelProvider provider = mock(ChatModelProvider.class);
        when(active.get()).thenReturn(provider);
        when(provider.chat(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("one girl wearing a blue dress in a garden")))));

        PonyPromptChatService.Reply draft = service.draft(request(List.of(
                        new Message("user", "เปลี่ยนชุดเป็นสีแดงและให้เป็นตอนกลางคืน"))),
                "เปลี่ยนชุดเป็นสีแดงและให้เป็นตอนกลางคืน", image(),
                new ChatModelGateway(active, null, null, false), "vision-model", "req",
                new ConversationId("pony-chat"));

        assertTrue(transformedBrief.get().contains("one girl wearing a blue dress in a garden"));
        assertTrue(transformedBrief.get().contains("เปลี่ยนชุดเป็นสีแดง"));
        assertTrue(draft.content().contains("score_9, score_8_up, score_7_up, 1girl, red dress, moonlight"));
        assertTrue(draft.content().contains("Negative prompt\n```text\nlow quality"));
        assertTrue(draft.attachments().isEmpty());
        assertNull(generated.get());

        ChatCompletionRequest followup = request(List.of(
                new Message("user", "เปลี่ยนชุดเป็นสีแดง"),
                new Message("assistant", draft.content()),
                new Message("user", "สร้างภาพจาก prompt นี้")));
        assertEquals(PonyPromptChatService.Action.GENERATE,
                service.action(followup, "สร้างภาพจาก prompt นี้", VisionInput.EMPTY));
        assertEquals(PonyPromptChatService.Action.GENERATE,
                service.action(followup, "เอา prompt นี้ไปสร้างภาพเลย", VisionInput.EMPTY));
        ChatCompletionRequest revision = request(List.of(
                new Message("user", "ก่อนหน้า"), new Message("assistant", draft.content()),
                new Message("user", "เปลี่ยนสีผมเป็นสีดำ")));
        assertEquals(PonyPromptChatService.Action.DRAFT,
                service.action(revision, "เปลี่ยนสีผมเป็นสีดำ", VisionInput.EMPTY));
        service.draft(revision, "เปลี่ยนสีผมเป็นสีดำ", VisionInput.EMPTY,
                null, "vision-model", "req", new ConversationId("pony-chat"));
        assertTrue(transformedBrief.get().contains("Apply the user's changes: เปลี่ยนสีผมเป็นสีดำ"));
        assertEquals(PonyPromptChatService.Action.NONE,
                service.action(request(List.of(new Message("user", "ก่อนหน้า"),
                        new Message("assistant", draft.content()),
                        new Message("user", "สร้าง prompt สำหรับภาพนี้อีกแบบ"))),
                        "สร้าง prompt สำหรับภาพนี้อีกแบบ", VisionInput.EMPTY));
        PonyPromptChatService.Reply result = service.generate(followup, "owner", new ConversationId("pony-chat"));
        assertEquals("score_9, score_8_up, score_7_up, 1girl, red dress, moonlight",
                generated.get().prompt());
        assertEquals(1, result.attachments().size());
        assertFalse(result.attachments().getFirst().url().isBlank());
    }

    private PonyPromptChatService service(PonyPromptTransformer transformer,
            AtomicReference<ImageGenerationRequest> generated) {
        StoryIllustrationProvider provider = new StoryIllustrationProvider() {
            @Override public GeneratedImage generate(String prompt) {
                throw new AssertionError("structured request expected");
            }
            @Override public GeneratedImage generate(ImageGenerationRequest request) {
                generated.set(request);
                return new GeneratedImage(new byte[] {
                        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1}, "test-model");
            }
            @Override public String effectiveNegativePrompt(String negativePrompt) {
                return "low quality";
            }
        };
        ImageGenerationTool tool = new ImageGenerationTool(provider,
                new GeneratedImageStore(directory, 1024, Clock.systemUTC()), 8000, 4_194_304L);
        return new PonyPromptChatService(transformer, tool);
    }

    private VisionInput image() {
        byte[] bytes = {(byte) 0x89, 0x50};
        return new VisionInput(List.of(new Media(MimeTypeUtils.parseMimeType("image/png"),
                new ByteArrayResource(bytes))), List.of(new VisionInput.Image("image/png", bytes)));
    }

    private ChatCompletionRequest request(List<Message> messages) {
        return new ChatCompletionRequest("mini-kun", messages, "pony-chat", false, null, null, null);
    }
}
