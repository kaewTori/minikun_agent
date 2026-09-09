package com.minikun.model.existing;

import java.util.Objects;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.minikun.model.ChatModelId;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;

import reactor.core.publisher.Flux;

@Component
@lombok.extern.slf4j.Slf4j
public final class ExistingChatModelProvider implements ChatModelProvider {
    private static final ModelCapabilities CAPABILITIES = new ModelCapabilities(true, true, true);

    private final ChatModel chatModel;

    public ExistingChatModelProvider(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chat model must not be null");
    }

    @Override
    public ChatModelId id() {
        return ChatModelId.EXISTING;
    }

    @Override
    public ModelCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public ChatResponse chat(Prompt prompt) {
        ChatResponse response = chatModel.call(prompt);
        logOutcome(prompt, response, false);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return chatModel.stream(prompt).doOnNext(response -> logOutcome(prompt, response, true));
    }

    private void logOutcome(Prompt prompt, ChatResponse response, boolean streaming) {
        if (response == null || response.getResult() == null) return;
        String reason = response.getResult().getMetadata().getFinishReason();
        if (streaming && (reason == null || reason.isBlank())) return;
        var options = prompt.getOptions();
        var usage = response.getMetadata().getUsage();
        log.info("process=model_generation event=outcome model={} response_id={} stream={} "
                        + "requested_max_tokens={} prompt_tokens={} completion_tokens={} finish_reason={} stop_count={}",
                response.getMetadata().getModel(), response.getMetadata().getId(), streaming,
                options == null ? null : options.getMaxTokens(),
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens(),
                reason == null || reason.isBlank() ? "unknown" : reason,
                options == null || options.getStopSequences() == null ? 0 : options.getStopSequences().size());
    }
}
