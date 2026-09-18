package com.minikun.voice;

import com.minikun.agent.minikun_agent.api.openai.ChatService;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** OpenAI-compatible audio endpoints plus one local voice-turn convenience endpoint. */
@RestController
@RequestMapping("/v1/audio")
@ConditionalOnBean(VoiceService.class)
public final class VoiceController {
    private final VoiceService voice;
    private final ChatService chat;

    public VoiceController(VoiceService voice, ChatService chat) {
        this.voice = Objects.requireNonNull(voice, "voice service must not be null");
        this.chat = Objects.requireNonNull(chat, "chat service must not be null");
    }

    @GetMapping("/status")
    public VoiceStatus status() {
        return voice.status();
    }

    @PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> transcription(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "whisper-1") String model,
            @RequestParam(defaultValue = "auto") String language,
            @RequestParam(defaultValue = "") String prompt,
            @RequestParam(name = "response_format", defaultValue = "json") String responseFormat) {
        VoiceTranscription result = transcribe(file, language, prompt);
        if ("text".equalsIgnoreCase(responseFormat)) {
            return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(result.text());
        }
        if (!"json".equalsIgnoreCase(responseFormat)) throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "response_format must be json or text");
        return ResponseEntity.ok(Map.of(
                "text", result.text(),
                "language", result.language(),
                "duration_seconds", result.durationSeconds(),
                "model", voice.status().transcriptionModel()));
    }

    @PostMapping(value = "/speech", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> speech(@RequestBody SpeechRequest request) {
        if (request == null) throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "speech request must not be empty");
        VoiceAudio result = voice.synthesize(request.input(), request.voice(),
                request.response_format(), request.speed());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("minikun-speech." + result.format()).build().toString())
                .body(result.data());
    }

    @PostMapping(value = "/voice-turns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceTurnResponse voiceTurn(
            @RequestPart("file") MultipartFile file,
            @RequestParam(name = "conversation_id", defaultValue = "") String conversationId,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "auto") String language,
            @RequestParam(defaultValue = "minikun") String voiceName,
            @RequestParam(name = "response_format", defaultValue = "wav") String responseFormat,
            @RequestParam(defaultValue = "1.0") double speed) {
        String selectedConversation = conversationId == null || conversationId.isBlank()
                ? "voice-" + UUID.randomUUID() : conversationId.trim();
        if (selectedConversation.length() > 200 || !selectedConversation.matches("[a-zA-Z0-9._:-]+")) {
            throw new VoiceException(VoiceErrorCode.INVALID_REQUEST, "conversation_id contains unsupported characters");
        }
        VoiceTranscription transcription = transcribe(file, language, "");
        ChatCompletionResponse chatResponse = chat.chatCompletion(new ChatCompletionRequest(
                "mini-kun", List.of(new Message("user", transcription.text())), selectedConversation,
                false, null, null, null, null, ownerId, "voice"), new ConversationId(selectedConversation));
        if (chatResponse.choices().isEmpty()) throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                "chat model returned no response");
        String answer = chatResponse.choices().getFirst().message().content();
        VoiceAudio audio = voice.synthesize(answer, voiceName, responseFormat, speed);
        return new VoiceTurnResponse(selectedConversation, transcription.text(), answer,
                audio.format(), audio.mediaType(), Base64.getEncoder().encodeToString(audio.data()));
    }

    private VoiceTranscription transcribe(MultipartFile file, String language, String prompt) {
        if (file == null || file.isEmpty()) throw new VoiceException(VoiceErrorCode.INVALID_AUDIO,
                "audio file must not be empty");
        if (file.getSize() > voice.status().maxInputBytes()) throw new VoiceException(VoiceErrorCode.INVALID_AUDIO,
                "audio file exceeds the configured size limit");
        try {
            return voice.transcribe(file.getOriginalFilename(), file.getContentType(), file.getBytes(), language, prompt);
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.INVALID_AUDIO, "audio upload could not be read");
        }
    }

    public record SpeechRequest(String model, String input, String voice, String response_format, Double speed) {}

    public record VoiceTurnResponse(
            String conversation_id,
            String transcript,
            String response,
            String audio_format,
            String audio_media_type,
            String audio_base64) {}
}
