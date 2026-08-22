package com.minikun.agent.minikun_agent.conversation;

import java.util.List;
import java.util.Objects;

import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;

/** Uses the small task model to merge newly aged-out turns into a concise rolling summary. */
public final class TaskModelConversationSummaryGenerator implements ConversationSummaryGenerator {
    private static final String NO_THINK_PREFIX = "/no_think\n";
    private final TaskModelProvider taskModelProvider;
    private final int maximumOutputTokens;

    public TaskModelConversationSummaryGenerator(
            TaskModelProvider taskModelProvider,
            int maximumOutputTokens) {
        this.taskModelProvider = Objects.requireNonNull(taskModelProvider, "task model provider must not be null");
        if (maximumOutputTokens <= 0) {
            throw new IllegalArgumentException("maximum output tokens must be positive");
        }
        this.maximumOutputTokens = maximumOutputTokens;
    }

    @Override
    public String update(String existingSummary, List<ChatMessage> newOlderMessages) {
        Objects.requireNonNull(newOlderMessages, "new older messages must not be null");
        if (newOlderMessages.isEmpty()) {
            return Objects.requireNonNullElse(existingSummary, "");
        }
        String prompt = NO_THINK_PREFIX + """
                Update a rolling conversation summary using only the supplied conversation evidence.
                Preserve the user's goals, decisions, preferences, named entities, references needed by later
                follow-ups, unresolved questions, promises, and important outcomes. Remove greetings, repetition,
                transient wording, and superseded details. Never invent facts. Use the primary language of the
                conversation. Output only the updated summary as concise bullet points, without a heading.

                Existing summary:
                """ + blankLabel(existingSummary) + "\n\nNew older messages:\n" + format(newOlderMessages);
        String response = taskModelProvider.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("user", prompt)), maximumOutputTokens, 0.0,
                TaskModelRequest.ResponseFormat.TEXT));
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("conversation summary model returned empty content");
        }
        return stripThinking(response);
    }

    private String format(List<ChatMessage> messages) {
        return messages.stream()
                .map(message -> message.role() + ": " + message.content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("(none)");
    }

    private String blankLabel(String summary) {
        return summary == null || summary.isBlank() ? "(none)" : summary.strip();
    }

    private String stripThinking(String response) {
        String normalized = response.strip();
        if (normalized.startsWith("<think>")) {
            int closing = normalized.indexOf("</think>");
            if (closing >= 0) {
                normalized = normalized.substring(closing + "</think>".length()).strip();
            }
        }
        return normalized;
    }
}
