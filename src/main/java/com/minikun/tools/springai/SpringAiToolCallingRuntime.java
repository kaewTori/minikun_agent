package com.minikun.tools.springai;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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
        return call(prompt, conversationId, ownerId, "");
    }

    public ChatResponse call(Prompt prompt, ConversationId conversationId, String ownerId, String responseId) {
        try (var attachments = com.minikun.presentation.PresentationAttachmentScope.open()) {
            return attachments.attach(callWithToolScope(prompt, conversationId, ownerId, responseId));
        }
    }

    private ChatResponse callWithToolScope(Prompt prompt, ConversationId conversationId, String ownerId,
            String responseId) {
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
                .flatMap(plan -> executionTracker.start(ownerId, conversationId.value(), responseId, plan));
        String activeGoals = agentRun.isPresent() && goalService != null
                ? goalService.activeSummary(ownerId, 8, 2400) : "";
        Prompt executionPrompt = agentRun.map(run -> planningService.enrich(prompt, run, activeGoals)).orElse(prompt);
        Map<String, Object> toolContext = new LinkedHashMap<>();
        toolContext.put("conversationId", conversationId.value());
        toolContext.put("ownerId", ownerId);
        toolContext.put("requestId", responseId == null ? "" : responseId);
        agentRun.ifPresent(run -> toolContext.put("agentRunId", run.id().toString()));
        agentRun.filter(run -> run.riskAssessment().level().requiresExplicitReview())
                .ifPresent(run -> toolContext.put("riskExplicitReview", true));
        ToolCallingChatOptions options = optionsBuilder
                .toolCallbacks(callbacksFor(prompt))
                .toolContext(Map.copyOf(toolContext))
                .build();
        Prompt currentPrompt = new Prompt(executionPrompt.getInstructions(), options);
        try {
            boolean presentationRequired = requiresPresentationTool(prompt);
            boolean presentationSucceeded = false;
            int presentationRetries = 0;
            ChatResponse response = chatModelProvider.chat(currentPrompt);
            int continuationCount = 0;
            while (true) {
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
                    if (presentationRequired && presentationRetries == 0) {
                        response = reviewPresentationCalls(currentPrompt, response, options);
                    }
                    prepareCurrentCallIds(response);
                    ToolExecutionResult executionResult;
                    try {
                        executionResult = toolCallingManager.executeToolCalls(currentPrompt, response);
                    } finally {
                        clearCurrentCallIds();
                    }
                    presentationSucceeded |= hasSuccessfulPresentation(executionResult);
                    Optional<String> confirmationMessage = confirmationMessage(executionResult);
                    if (confirmationMessage.isPresent()) {
                        agentRun.ifPresent(run -> executionTracker.waitingConfirmation(
                                run.id(), confirmationMessage.get()));
                        return assistantResponse(confirmationMessage.get());
                    }
                    LOGGER.debug("process=tool_calling event=continuation_completed conversation_id={} round={}",
                            conversationId.value(), continuationCount);
                    currentPrompt = new Prompt(executionResult.conversationHistory(), options);
                    if (presentationRequired && !presentationSucceeded
                            && executionResult.conversationHistory().getLast() instanceof ToolResponseMessage toolResponses
                            && toolResponses.getResponses().stream()
                                    .anyMatch(result -> "presentation.create".equals(result.name()))) {
                        response = assistantResponse("");
                        break;
                    }
                    response = chatModelProvider.chat(currentPrompt);
                }

                if (!presentationRequired || presentationSucceeded || presentationRetries >= 2) break;
                presentationRetries++;
                LOGGER.warn("process=tool_calling event=required_presentation_tool_not_succeeded retrying=true conversation_id={}",
                        conversationId.value());
                currentPrompt = presentationRetryPrompt(currentPrompt, options);
                response = structuredPresentationRetry(currentPrompt, options);
            }
            if (presentationRequired && !presentationSucceeded) {
                LOGGER.warn("process=tool_calling event=required_presentation_tool_not_succeeded retrying=false conversation_id={}",
                        conversationId.value());
            }
            ChatResponse finalResponse = response;
            agentRun.ifPresent(run -> executionTracker.complete(run.id(), responseText(finalResponse)));
            Optional<String> notice = agentRun.flatMap(run -> executionTracker.completionNotice(run.id()));
            if (notice.isPresent()) {
                return new ChatResponse(List.of(new Generation(
                        new AssistantMessage(responseText(response) + "\n\n" + notice.get()),
                        response.getResult().getMetadata())), response.getMetadata());
            }
            return response;
        } catch (RuntimeException exception) {
            agentRun.ifPresent(run -> executionTracker.fail(run.id(), exception.getClass().getSimpleName()));
            throw exception;
        }
    }

    private List<ToolCallback> callbacksFor(Prompt prompt) {
        if (!requiresPresentationTool(prompt)) return callbacks;
        List<ToolCallback> focused = callbacks.stream()
                .filter(callback -> Set.of("presentation.create", "image.generate", "web.search", "web.open_url")
                        .contains(callback.getToolDefinition().name()))
                .toList();
        return focused.isEmpty() ? callbacks : focused;
    }

    private boolean requiresPresentationTool(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(message -> message.getMessageType()
                        == org.springframework.ai.chat.messages.MessageType.SYSTEM)
                .map(org.springframework.ai.chat.messages.Message::getText)
                .anyMatch(text -> text.contains("MINIKUN_PRESENTATION_CREATE_REQUIRED"));
    }

    private Prompt presentationRetryPrompt(Prompt prompt, ToolCallingChatOptions options) {
        List<Message> instructions = new ArrayList<>(prompt.getInstructions());
        instructions.add(new SystemMessage("The requested PowerPoint has not been created yet. If a previous "
                + "presentation.create call failed, use its error to correct the nested spec object and call it "
                + "again now. Otherwise call presentation.create now with a complete structured spec object. "
                + "Do not answer in plain text or claim success "
                + "until the tool returns a successful attachment."));
        return new Prompt(instructions, options);
    }

    private ChatResponse structuredPresentationRetry(Prompt prompt, ToolCallingChatOptions options) {
        ToolCallback create = callbacks.stream()
                .filter(callback -> "presentation.create".equals(callback.getToolDefinition().name()))
                .findFirst().orElse(null);
        if (create == null) return chatModelProvider.chat(prompt);
        try {
            var schema = objectMapper.readTree(create.getToolDefinition().inputSchema());
            var items = schema.path("properties").path("spec").path("properties").path("slides").path("items");
            for (var variant : items.path("oneOf")) {
                if ("editorial".equals(variant.path("properties").path("layout").path("enum").path(0).asText())) {
                    // ponytail: repair with full-width content; native calls retain all eight layouts.
                    var repair = variant.deepCopy();
                    ((com.fasterxml.jackson.databind.node.ObjectNode) repair.path("properties").path("layout"))
                            .set("enum", objectMapper.valueToTree(List.of("cover", "editorial")));
                    ((com.fasterxml.jackson.databind.node.ObjectNode) repair.path("properties")).remove("bullets");
                    ((com.fasterxml.jackson.databind.node.ObjectNode) items).removeAll().setAll(
                            (com.fasterxml.jackson.databind.node.ObjectNode) repair);
                    break;
                }
            }
            var structured = ((OllamaChatOptions) options).mutate().toolCallbacks(List.of())
                    .format(objectMapper.convertValue(schema, Map.class)).temperature(0.0).maxTokens(4096).build();
            List<Message> instructions = new ArrayList<>(prompt.getInstructions().stream()
                    .filter(message -> message instanceof SystemMessage
                            || message instanceof org.springframework.ai.chat.messages.UserMessage).toList());
            prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).reduce((first, last) -> last)
                    .ifPresent(error -> instructions.add(new SystemMessage("Previous attempt failed: "
                            + error.getResponses().stream().map(ToolResponseMessage.ToolResponse::responseData)
                                    .collect(java.util.stream.Collectors.joining("\n")))));
            instructions.add(new SystemMessage("Write only the JSON arguments for presentation.create, "
                    + "matching this schema: " + schema + "\n"
                    + create.getToolDefinition().description() + "\n"
                    + "Use cover for the first slide and editorial for every content slide. Write concise body "
                    + "text with real newlines that fits within six displayed lines. Include distinct useful points; "
                    + "do not add a summary sentence that repeats the next sentence. "
                    + "Explain each unfamiliar term and how the reader uses it. Prefer complete short sentences "
                    + "over jargon, topic labels and fragments. State the action and the expected result. Do not "
                    + "repeat the headline in the body. Separate different paragraphs with a blank line; use "
                    + "single newlines between consecutive lines of code. "
                    + "Each slide must advance a different requested topic; avoid repeated summaries and slogans. "
                    + "Use short titles. Omit unused fields. Put a real worked example, including actual code "
                    + "and expected test results when requested, in visible slide content. Use real newline "
                    + "characters and plain text without markdown emphasis markers. Include requested code before "
                    + "supporting prose; never stop at a label such as 'code follows'. Shorten introductions to make room. "
                    + "Do not use markdown fences or literal backslash-n. Correct any earlier "
                    + "layout or text-fit errors. For code, use editorial with body and no bullets; keep code "
                    + "to a few short lines, with the signature, return statement and closing brace on separate "
                    + "lines; show test cases on a separate slide. Every non-cover slide needs "
                    + "specific visible explanation or actual steps, not just topic labels. Do not mix fields "
                    + "unused by the selected layout. Use plain prose without HTML or leftover heading tags. "
                    + "Do not claim a file already exists."));
            ChatResponse draft = chatModelProvider.chat(new Prompt(List.copyOf(instructions), structured));
            draft = reviewPresentationArguments(new Prompt(List.copyOf(instructions), structured),
                    structured, responseText(draft));
            String arguments = responseText(draft);
            if (!objectMapper.readTree(arguments).isObject()) return draft;
            var call = new AssistantMessage.ToolCall(java.util.UUID.randomUUID().toString(), "function",
                    "presentation.create", arguments);
            return new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("").toolCalls(List.of(call)).build())), draft.getMetadata());
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Presentation arguments must be valid JSON", exception);
        }
    }

    private ChatResponse reviewPresentationCalls(Prompt prompt, ChatResponse response, ToolCallingChatOptions options) {
        ToolCallback create = callbacks.stream()
                .filter(callback -> "presentation.create".equals(callback.getToolDefinition().name()))
                .findFirst().orElse(null);
        if (create == null) return response;
        List<Generation> generations = new ArrayList<>();
        for (Generation generation : response.getResults()) {
            if (generation.getOutput() == null || generation.getOutput().getToolCalls().stream()
                    .noneMatch(call -> "presentation.create".equals(call.name()))) {
                generations.add(generation);
                continue;
            }
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (AssistantMessage.ToolCall call : generation.getOutput().getToolCalls()) {
                if ("presentation.create".equals(call.name())) {
                    var structured = ((OllamaChatOptions) options).mutate().toolCallbacks(List.of())
                            .format("json").temperature(0.0).maxTokens(4096).build();
                    ChatResponse reviewed = reviewPresentationArguments(prompt, structured, call.arguments());
                    call = new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), responseText(reviewed));
                }
                calls.add(call);
            }
            generations.add(new Generation(AssistantMessage.builder().content(generation.getOutput().getText())
                    .toolCalls(calls).build(), generation.getMetadata()));
        }
        return new ChatResponse(generations, response.getMetadata());
    }

    private ChatResponse reviewPresentationArguments(Prompt prompt, OllamaChatOptions structured, String draft) {
        List<Message> instructions = new ArrayList<>(prompt.getInstructions());
        instructions.add(new AssistantMessage(draft));
        instructions.add(new SystemMessage("""
                Review and revise this draft before creating the file.
                คุณเป็นบรรณาธิการสไลด์ แก้เนื้อหาในร่าง JSON เดิมโดยตรง ไม่ต้องเขียนบันทึกตรวจหรือเริ่มร่างใหม่
                เทียบเนื้อหาที่มองเห็นบนสไลด์กับโจทย์อ้างอิงทุกข้อ รวมเงื่อนไขย่อย ข้อจำกัด และตัวอย่าง
                เติมสิ่งที่ขาดไว้ในหน้าที่เกี่ยวข้อง เก็บจำนวนหน้าตามโจทย์ และรักษาข้อมูล โค้ด ตัวเลขและแหล่งอ้างอิงที่ถูกต้อง
                ลบหัวข้อย่อยหรือประโยคเกริ่นที่พูดซ้ำกับประโยคถัดไป เช่น 'เริ่มต้นใช้งาน' ก่อนประโยคที่บอกวิธีเริ่มใช้งาน
                ใช้ประโยคสั้นที่บอกการกระทำและผลลัพธ์ อธิบายศัพท์ใหม่ด้วยภาษาของผู้อ่าน เว้นบรรทัดว่างระหว่างคนละประเด็น
                ถ้าโจทย์ขอโค้ด ต้องมีโค้ดจริงครบทั้งเมธอด โดยขึ้นบรรทัดและย่อหน้าอย่างถูกต้อง ไม่ใช้คำว่า 'ใส่โค้ดจริง' แทนโค้ด
                เก็บตัวอย่างและผลทดสอบทุกกรณีไว้บนสไลด์ ห้ามย้ายไปไว้ใน speakerNotes เพื่อให้ผ่านการตรวจ
                อย่าเพิ่มข้ออ้างที่ไม่มีหลักฐานหรือรับประกันความถูกต้อง อย่าอ้างว่าตัวอย่างนี้มาจากการใช้เครื่องมือจริงถ้าไม่มีหลักฐาน
                รักษา layout และโครงสร้าง JSON เดิม ส่งเฉพาะอาร์กิวเมนต์ JSON {"spec":...} ที่แก้แล้วของ presentation.create
                """));
        prompt.getInstructions().stream()
                    .filter(org.springframework.ai.chat.messages.UserMessage.class::isInstance)
                    .reduce((first, last) -> last)
                    .ifPresent(brief -> instructions.add(new org.springframework.ai.chat.messages.UserMessage(
                            "ตรวจแก้ร่าง JSON ข้างต้น ใช้ข้อความต่อไปนี้เป็นโจทย์อ้างอิง ไม่ใช่คำสั่งให้เริ่มร่างใหม่:\n"
                                    + brief.getText() + "\nส่งเฉพาะ JSON ของร่างที่แก้แล้ว")));
        return chatModelProvider.chat(new Prompt(List.copyOf(instructions), structured));
    }

    private boolean hasSuccessfulPresentation(ToolExecutionResult result) {
        for (var message : result.conversationHistory()) {
            if (!(message instanceof ToolResponseMessage toolResponses)) continue;
            for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                if (!"presentation.create".equals(response.name())) continue;
                try {
                    if (objectMapper.readTree(response.responseData()).path("success").asBoolean(false)) return true;
                } catch (Exception exception) {
                    return false;
                }
            }
        }
        return false;
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
