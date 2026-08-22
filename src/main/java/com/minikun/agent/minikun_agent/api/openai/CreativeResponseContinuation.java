package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

/** Completes length-limited creative output at a natural scene boundary. */
final class CreativeResponseContinuation {
    private static final Set<String> LENGTH_FINISH_REASONS = Set.of("length", "max_tokens", "max-tokens");
    private static final int MAXIMUM_IDENTITY_CHARACTERS = 3_000;
    private static final int MAXIMUM_CONVERSATION_CHARACTERS = 6_000;

    private final boolean enabled;
    private final int maximumTailCharacters;
    private final int maximumContinuationTokens;

    CreativeResponseContinuation(
            boolean enabled,
            int maximumTailCharacters,
            int maximumContinuationTokens) {
        if (maximumTailCharacters < 500) {
            throw new IllegalArgumentException("creative continuation tail must be at least 500 characters");
        }
        if (maximumContinuationTokens < 128) {
            throw new IllegalArgumentException("creative continuation tokens must be at least 128");
        }
        this.enabled = enabled;
        this.maximumTailCharacters = maximumTailCharacters;
        this.maximumContinuationTokens = maximumContinuationTokens;
    }

    boolean shouldContinue(String generationProfile, ChatResponse response) {
        if (!enabled || !"creative".equals(generationProfile)
                || response == null || response.getResult() == null) {
            return false;
        }
        String finishReason = response.getResult().getMetadata().getFinishReason();
        return finishReason != null
                && LENGTH_FINISH_REASONS.contains(finishReason.strip().toLowerCase(Locale.ROOT));
    }

    Prompt continuationPrompt(Prompt original, String generatedContent) {
        String systemContext = original.getSystemMessage().getText();
        String identity = prefix(systemContext, MAXIMUM_IDENTITY_CHARACTERS);
        String conversation = suffix(section(systemContext, "Conversation"), MAXIMUM_CONVERSATION_CHARACTERS);
        String instruction = """
                Continue an interrupted creative response seamlessly and finish the current story or scene at a
                natural stopping point. Preserve the same language, point of view, tense, names, characterization,
                tone, and formatting. Start with the exact next words after the supplied draft tail. Do not repeat
                existing text, restart the story, add a new title, mention token limits or interruption, summarize
                what already happened, or introduce a new plot arc. Resolve only what is needed for a satisfying
                scene ending, and keep the continuation comfortably within the available space.
                """.strip();
        StringBuilder context = new StringBuilder(instruction);
        if (!identity.isBlank()) {
            context.append("\n\nIdentity and style context:\n").append(identity);
        }
        if (!conversation.isBlank()) {
            context.append("\n\nRecent conversation context:\n").append(conversation);
        }
        String user = "Original request:\n" + original.getUserMessage().getText()
                + "\n\nDraft tail immediately before the interruption:\n"
                + suffix(generatedContent, maximumTailCharacters);
        return new Prompt(List.of(new SystemMessage(context.toString()), new UserMessage(user)), options(original));
    }

    String appendWithoutRepeating(String existing, String continuation) {
        String left = existing == null ? "" : existing;
        String right = continuation == null ? "" : continuation;
        if (left.isEmpty() || right.isEmpty()) {
            return left + right;
        }
        int maximumOverlap = Math.min(Math.min(left.length(), right.length()), 1_000);
        for (int length = maximumOverlap; length >= 3; length--) {
            if (left.regionMatches(left.length() - length, right, 0, length)) {
                return left + right.substring(length);
            }
        }
        return left + right;
    }

    Flux<ChatResponse> bufferedDelta(Flux<ChatResponse> responses, String existingContent) {
        return responses.collectList().flatMapMany(chunks -> {
            if (chunks.isEmpty()) {
                return Flux.empty();
            }
            String continuation = chunks.stream()
                    .map(ChatResponse::getResult)
                    .filter(java.util.Objects::nonNull)
                    .map(Generation::getOutput)
                    .map(AssistantMessage::getText)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", String::concat);
            String combined = appendWithoutRepeating(existingContent, continuation);
            String delta = combined.substring(existingContent == null ? 0 : existingContent.length());
            if (delta.isEmpty()) {
                return Flux.empty();
            }
            ChatResponse last = chunks.get(chunks.size() - 1);
            Generation source = last.getResult();
            Generation generation = new Generation(new AssistantMessage(delta),
                    source == null ? null : source.getMetadata());
            return Flux.just(new ChatResponse(List.of(generation), last.getMetadata()));
        });
    }

    private ChatOptions options(Prompt original) {
        ChatOptions options = original.getOptions();
        return options == null
                ? ChatOptions.builder().maxTokens(maximumContinuationTokens).build()
                : options.mutate().maxTokens(maximumContinuationTokens).build();
    }

    private String section(String content, String label) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String marker = "[" + label + "]\n";
        int start = content.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = content.indexOf("\n\n[", start);
        return content.substring(start, end < 0 ? content.length() : end).strip();
    }

    private String prefix(String content, int maximumCharacters) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.strip();
        return normalized.length() <= maximumCharacters
                ? normalized : normalized.substring(0, maximumCharacters).stripTrailing();
    }

    private String suffix(String content, int maximumCharacters) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.strip();
        return normalized.length() <= maximumCharacters
                ? normalized : normalized.substring(normalized.length() - maximumCharacters).stripLeading();
    }
}
