package com.minikun.model.task.title;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public final class TitleGenerationService {
    public static final String FALLBACK_TITLE = "New Conversation";

    private final TitleGenerationProvider provider;

    public TitleGenerationService(TitleGenerationProvider provider) {
        this.provider = Objects.requireNonNull(provider, "title provider must not be null");
    }

    public String generateTitle(List<ChatMessage> messages) {
        long started = System.nanoTime();
        try {
            String title = provider.generateTitle(messages);
            if (title == null || title.isBlank()) {
                throw new IllegalStateException("title provider returned an empty title");
            }
            log.info("title_generation_provider=task_model success=true latency_ms={}", elapsedMillis(started));
            return title.trim();
        } catch (RuntimeException exception) {
            log.warn("title_generation_provider=task_model success=false latency_ms={}", elapsedMillis(started), exception);
            return FALLBACK_TITLE;
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
