package com.minikun.model.task.title;

import java.util.List;
import java.util.Objects;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class OllamaTitleGenerationProvider implements TitleGenerationProvider {
    private static final int MAX_OUTPUT_TOKENS = 32;
    private final TitlePromptBuilder promptBuilder;
    private final TaskModelProvider taskModelProvider;

    public OllamaTitleGenerationProvider(TaskModelProvider taskModelProvider,
            TitlePromptBuilder promptBuilder) {
        this.taskModelProvider = Objects.requireNonNull(taskModelProvider,
                "taskModelProvider must not be null");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder must not be null");
    }

    @Override
    public String generateTitle(List<ChatMessage> messages) {
        String prompt = promptBuilder.build(messages);
        String response = taskModelProvider.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("user", prompt)), MAX_OUTPUT_TOKENS, 0.0,
                TaskModelRequest.ResponseFormat.TEXT));
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("task model returned an empty title");
        }
        return response.trim().replaceAll("^\\\"|\\\"$", "");
    }
}
