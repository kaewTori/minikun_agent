package com.minikun.tools.springai;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelProvider;
import com.minikun.tools.Tool;
import com.minikun.tools.ToolExecutor;
import com.minikun.agent.execution.AgentExecutionTracker;
import com.minikun.agent.execution.AgentPlanningService;
import com.minikun.agent.execution.AgentRun;
import com.minikun.goal.GoalService;
import com.minikun.planner.PlannerConfirmationService;

@Component
public final class SpringAiToolCallingRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringAiToolCallingRuntime.class);

    private final ChatModelProvider chatModelProvider;
    private final ToolCallingManager toolCallingManager;
    private final List<ToolCallback> callbacks;
    private final ObjectMapper objectMapper;
    private final AgentPlanningService planningService;
    private final AgentExecutionTracker executionTracker;
    private final int maxToolContinuations;
    private final GoalService goalService;

    @Autowired
    public SpringAiToolCallingRuntime(
            ActiveChatModelProvider activeChatModelProvider,
            List<Tool> tools,
            ToolExecutor toolExecutor,
            ObjectMapper objectMapper,
            AgentPlanningService planningService,
            AgentExecutionTracker executionTracker,
            @Value("${minikun.agent.execution.max-tool-continuations:8}") int maxToolContinuations,
            org.springframework.beans.factory.ObjectProvider<GoalService> goalService,
            org.springframework.beans.factory.ObjectProvider<PlannerConfirmationService> confirmations) {
        this.chatModelProvider = Objects.requireNonNull(
                activeChatModelProvider, "active chat model provider must not be null").get();
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.planningService = Objects.requireNonNull(planningService, "planning service must not be null");
        this.executionTracker = Objects.requireNonNull(executionTracker, "execution tracker must not be null");
        this.maxToolContinuations = Math.max(1, Math.min(maxToolContinuations, 20));
        this.goalService = goalService == null ? null : goalService.getIfAvailable();
        PlannerConfirmationService confirmationService = confirmations == null ? null : confirmations.getIfAvailable();
        Objects.requireNonNull(tools, "tools must not be null");
        this.callbacks = tools.stream()
                .sorted((left, right) -> left.definition().name().compareTo(right.definition().name()))
                .map(tool -> new SpringAiToolCallback(
                        tool, toolExecutor, objectMapper, executionTracker, confirmationService))
                .map(callback -> (ToolCallback) callback)
                .toList();
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    public SpringAiToolCallingRuntime(
            ActiveChatModelProvider activeChatModelProvider,
            List<Tool> tools,
            ToolExecutor toolExecutor,
            ObjectMapper objectMapper) {
        this(activeChatModelProvider, tools, toolExecutor, objectMapper,
                new AgentPlanningService(false, 8, 2000), AgentExecutionTracker.noop(), 4, null, null);
    }

    public ChatResponse call(Prompt prompt, ConversationId conversationId) {
        return call(prompt, conversationId, "default");
    }

    public ChatResponse call(Prompt prompt, ConversationId conversationId, String ownerId) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        Objects.requireNonNull(ownerId, "owner id must not be null");
        if (!chatModelProvider.capabilities().toolCalling()) {
            throw new IllegalStateException(
                    "Chat model provider does not support tool calling: " + chatModelProvider.id());
        }
        // OllamaChatModel casts chat options to OllamaChatOptions. The generic
        // DefaultToolCallingChatOptions is not compatible with that adapter,
        // even though both implement ToolCallingChatOptions.
        OllamaChatOptions.Builder optionsBuilder;
        if (prompt.getOptions() instanceof OllamaChatOptions ollamaOptions) {
            // Preserve every request-scoped Ollama option (especially numCtx). Rebuilding
            // from the generic ChatOptions view silently resets Ollama-specific settings.
            optionsBuilder = ollamaOptions.mutate();
        } else {
            optionsBuilder = OllamaChatOptions.builder();
            copyChatOptions(prompt.getOptions(), optionsBuilder);
        }
        Optional<AgentRun> agentRun = planningService.plan(prompt)
                .flatMap(plan -> executionTracker.start(ownerId, conversationId.value(), plan));
        String activeGoals = agentRun.isPresent() && goalService != null
                ? goalService.activeSummary(ownerId, 8, 2400) : "";
        Prompt executionPrompt = agentRun.map(run -> planningService.enrich(prompt, run, activeGoals)).orElse(prompt);
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put("conversationId", conversationId.value());
        toolContext.put("ownerId", ownerId);
        agentRun.ifPresent(run -> toolContext.put("agentRunId", run.id().toString()));
        agentRun.filter(run -> run.riskAssessment().level().requiresExplicitReview())
                .ifPresent(run -> toolContext.put("riskExplicitReview", true));
        ToolCallingChatOptions options = optionsBuilder
                .toolCallbacks(callbacks)
                .toolContext(Map.copyOf(toolContext))
                .build();
        Prompt currentPrompt = new Prompt(executionPrompt.getInstructions(), options);
        try {
            ChatResponse response = chatModelProvider.chat(currentPrompt);
            int continuationCount = 0;
            while (hasToolCalls(response)) {
                if (continuationCount >= maxToolContinuations) {
                    LOGGER.warn("process=tool_calling event=continuation_limit_reached conversation_id={} rounds={}",
                            conversationId.value(), continuationCount);
                    if (agentRun.isPresent()) {
                        executionTracker.limitReached(agentRun.get().id(),
                                "tool continuation limit reached after " + continuationCount + " rounds");
                    }
                    return continuationLimitResponse();
                }

                continuationCount++;
                prepareCurrentCallIds(response);
                ToolExecutionResult executionResult;
                try {
                    executionResult = toolCallingManager.executeToolCalls(currentPrompt, response);
                } finally {
                    clearCurrentCallIds();
                }
                Optional<String> confirmationMessage = confirmationMessage(executionResult);
                if (confirmationMessage.isPresent()) {
                    agentRun.ifPresent(run -> executionTracker.waitingConfirmation(
                            run.id(), confirmationMessage.get()));
                    return assistantResponse(confirmationMessage.get());
                }
                LOGGER.debug("process=tool_calling event=continuation_completed conversation_id={} round={}",
                        conversationId.value(), continuationCount);
                currentPrompt = new Prompt(executionResult.conversationHistory(), options);
                response = chatModelProvider.chat(currentPrompt);
            }
            ChatResponse finalResponse = response;
            agentRun.ifPresent(run -> executionTracker.complete(run.id(), responseText(finalResponse)));
            return response;
        } catch (RuntimeException exception) {
            agentRun.ifPresent(run -> executionTracker.fail(run.id(), exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private void prepareCurrentCallIds(ChatResponse response) {
        List<AssistantMessage.ToolCall> toolCalls = response.getResults().stream()
                .filter(generation -> generation.getOutput() != null)
                .flatMap(generation -> generation.getOutput().getToolCalls().stream())
                .toList();
        callbacks.stream()
                .filter(SpringAiToolCallback.class::isInstance)
                .map(SpringAiToolCallback.class::cast)
                .forEach(callback -> callback.setCurrentCallIds(toolCalls.stream()
                        .filter(toolCall -> callback.getToolDefinition().name().equals(toolCall.name()))
                        .map(AssistantMessage.ToolCall::id)
                        .toList()));
    }

    private void clearCurrentCallIds() {
        callbacks.stream()
                .filter(SpringAiToolCallback.class::isInstance)
                .map(SpringAiToolCallback.class::cast)
                .forEach(SpringAiToolCallback::clearCurrentCallId);
    }

    private ChatResponse continuationLimitResponse() {
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage(
                        "ขออภัยครับ ตอนนี้การประมวลผลด้วยเครื่องมือยังไม่เสร็จสมบูรณ์ "
                                + "มินิคุงจึงยังไม่ควรสรุปข้อมูลแทนครับ"))));
    }

    private Optional<String> confirmationMessage(ToolExecutionResult executionResult) {
        for (var message : executionResult.conversationHistory()) {
            if (!(message instanceof ToolResponseMessage toolResponseMessage)) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                try {
                    var root = objectMapper.readTree(response.responseData());
                    var result = root.path("result");
                    if (result.path("requires_confirmation").asBoolean(false)) {
                        String messageText = result.path("message").asText("").trim();
                        return Optional.of(messageText.isBlank()
                                ? "รายการนี้ยังไม่ได้บันทึกครับ พี่สาวยืนยันให้มินิคุงดำเนินการต่อได้ไหมครับ"
                                : messageText);
                    }
                } catch (Exception ignored) {
                    // A non-JSON or unrelated tool result should continue through the model normally.
                }
            }
        }
        return Optional.empty();
    }

    private ChatResponse assistantResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private void copyChatOptions(ChatOptions source, ToolCallingChatOptions.Builder<?> target) {
        if (source == null) {
            return;
        }
        target.model(source.getModel())
                .frequencyPenalty(source.getFrequencyPenalty())
                .maxTokens(source.getMaxTokens())
                .presencePenalty(source.getPresencePenalty())
                .stopSequences(source.getStopSequences())
                .temperature(source.getTemperature())
                .topK(source.getTopK())
                .topP(source.getTopP());
    }

    private boolean hasToolCalls(ChatResponse response) {
        return toolCallCount(response) > 0;
    }

    private int toolCallCount(ChatResponse response) {
        if (response == null) {
            return 0;
        }
        return response.getResults().stream()
                .filter(generation -> generation.getOutput() != null)
                .mapToInt(generation -> generation.getOutput().getToolCalls().size())
                .sum();
    }

    private String responseText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) return "";
        return response.getResult().getOutput().getText();
    }
}
