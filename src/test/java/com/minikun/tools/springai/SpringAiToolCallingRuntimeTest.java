package com.minikun.tools.springai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.mockito.ArgumentCaptor;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.ActiveModelConfiguration;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.model.ChatModelId;
import com.minikun.model.DefaultActiveChatModelProvider;
import com.minikun.model.DefaultChatModelProviderRegistry;
import com.minikun.model.ChatModelProvider;
import com.minikun.model.ModelCapabilities;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.tools.CalculatorAddTool;
import com.minikun.tools.DefaultToolExecutor;
import com.minikun.tools.DefaultToolRegistry;
import com.minikun.tools.Tool;
import com.minikun.tools.ToolDefinition;
import com.minikun.tools.ToolExecutor;
import com.minikun.tools.ToolResult;
import com.minikun.tools.ToolErrorCode;
import com.minikun.agent.execution.AgentExecutionStep;
import com.minikun.agent.execution.AgentExecutionTracker;
import com.minikun.agent.execution.AgentStepStatus;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.tools.ToolParameter;
import com.minikun.tools.ToolParameterType;

class SpringAiToolCallingRuntimeTest {
    @Test
    void adviceCannotReachBrokerActionsAndSetupResponsesNeverClaimThatMarketDataWasRetrieved() throws Exception {
        ToolExecutor executor = mock(ToolExecutor.class);
        var callback = new SpringAiToolCallback(namedTool("investment.data"), executor, new ObjectMapper());
        var context = new ToolContext(Map.of("conversationId", "advice", "ownerId", "owner-a",
                "investmentAdviceReadOnly", true));
        var rejected = new ObjectMapper().readTree(callback.call(
                "{\"action\":\"paper_order\",\"confirmed\":true}", context));
        assertEquals(false, rejected.path("success").asBoolean());
        assertEquals("REVIEW_REQUIRED", rejected.path("error_code").asText());
        org.mockito.Mockito.verifyNoInteractions(executor);
        when(executor.execute(any(), any())).thenReturn(ToolResult.success(
                Map.of("status", "not_configured", "setup_required", List.of("set market key"))));
        var unavailable = new ObjectMapper().readTree(callback.call("{\"action\":\"quotes\"}", context));
        assertEquals("not_configured", unavailable.path("result").path("status").asText());
        assertEquals(true, unavailable.path("assistant_instruction").asText().contains("was not retrieved"));
        assertEquals(false, unavailable.path("assistant_instruction").asText().contains("This is a verified result"));
    }

    @Test
    void suppliesTheAuthenticatedPortfolioBeforeAdviceEvenWhenTheModelNeverCallsATool() throws Exception {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        Tool review = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("investment.analyze", "read portfolio", Map.of("action",
                        new ToolParameter("action", ToolParameterType.STRING, true, "review")));
            }
            @Override public ToolResult execute(com.minikun.tools.ToolCallContext context, Map<String, Object> arguments) {
                assertEquals("owner-a", context.ownerId());
                assertEquals("review", arguments.get("action"));
                reads.incrementAndGet();
                return ToolResult.success(Map.of("portfolio", Map.of("baseCurrency", "USD", "totalOpenCostBasis", 20,
                        "positions", List.of(Map.of("symbol", "AMZN", "costBasis", 10, "costAllocationPercent", 50),
                                Map.of("symbol", "VTI", "costBasis", 10, "costAllocationPercent", 50)))));
            }
        };
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            assertEquals(1, reads.get());
            Prompt prompt = invocation.getArgument(0);
            assertEquals(List.of(), ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks());
            assertEquals(true, prompt.getInstructions().getLast().getText().contains("AMZN"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("""
                    {"allocations":[{"symbol":"VTI","weight":100}],"rationale":"เสริมแกนพอร์ตของเรา",
                    "avoid_symbols":["AMZN"],"sources":[]}
                    """))));
        });
        var runtime = new SpringAiToolCallingRuntime(new DefaultActiveChatModelProvider(
                new ActiveModelConfiguration(ChatModelId.EXISTING),
                new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(model)))),
                List.of(review, namedTool("investment.manage")),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(review))), new ObjectMapper());
        runtime.call(new Prompt(List.of(new SystemMessage("MINIKUN_INVESTMENT_ADVICE_REQUIRED"),
                new UserMessage("เรามีงบอยู่ 1000 บาท เอาไปลงทุนอะไรเพิ่มดี"))), new ConversationId("advice"), "owner-a");
        assertEquals(1, reads.get());
    }

    @Test
    void suppliesFxForTheLatestUserBudgetAndNeverUsesAssistantAmountsOrGuessesSplitBudgets() throws Exception {
        var investments = mock(com.minikun.investment.InvestmentService.class);
        var portfolio = new com.minikun.investment.PortfolioSummary("owner-a", "USD", "AVERAGE_COST",
                java.math.BigDecimal.TEN, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                List.of(new com.minikun.investment.PortfolioPosition("VTI", "Vanguard", "ETF", "USD",
                        java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, java.math.BigDecimal.TEN,
                        new java.math.BigDecimal("100"), java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO)),
                null, List.of());
        when(investments.summary("owner-a")).thenReturn(portfolio);
        var external = mock(com.minikun.investment.InvestmentExternalDataService.class);
        when(external.latestFxRate("THB", "USD")).thenReturn(new com.minikun.investment.InvestmentExternalDataService.FxRate(
                "THB", "USD", new java.math.BigDecimal("0.03"), java.time.LocalDate.of(2026, 10, 6), "test"));
        List<Tool> tools = List.of(new com.minikun.tools.InvestmentAnalyzeTool(investments),
                new com.minikun.tools.InvestmentDataTool(investments, external, mock(PlannerConfirmationService.class)));
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"allocations":[{"symbol":"VTI","weight":100}],"rationale":"เติมตามแผนเดิม",
                "avoid_symbols":[],"sources":[]}
                """)))));
        var runtime = new SpringAiToolCallingRuntime(new DefaultActiveChatModelProvider(
                new ActiveModelConfiguration(ChatModelId.EXISTING),
                new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(model)))),
                tools, new DefaultToolExecutor(new DefaultToolRegistry(tools)), new ObjectMapper().findAndRegisterModules());
        var history = new ArrayList<Message>(List.of(new SystemMessage("MINIKUN_INVESTMENT_ADVICE_REQUIRED"),
                new UserMessage("เรามี 5000 บาทเติมอะไรดี"), new AssistantMessage("VTI 5000 บาท"),
                new UserMessage("เปลี่ยนใหม่เป็น 3800 บาท"), new AssistantMessage("เลขผิด 9999 บาท")));
        history.add(new UserMessage("ขอยอดเป็น $ หน่อย"));
        runtime.call(new Prompt(history), new ConversationId("fx"), "owner-a");
        ArgumentCaptor<Prompt> captured = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captured.capture());
        var fx = new ObjectMapper().readTree(captured.getValue().getInstructions().getLast().getText()).path("fx");
        assertEquals(3800, fx.path("amount").asInt());
        assertEquals(0, new java.math.BigDecimal("114.00").compareTo(fx.path("converted_amount").decimalValue()));
        assertEquals("THB", fx.path("base_currency").asText());
        assertEquals("USD", fx.path("quote_currency").asText());
        org.mockito.Mockito.clearInvocations(external);
        history.add(new UserMessage("เปลี่ยนเป็น 3000 บาท และ 800 บาท"));
        runtime.call(new Prompt(history), new ConversationId("ambiguous-fx"), "owner-a");
        org.mockito.Mockito.verifyNoInteractions(external);
    }

    @Test
    void limitsPresentationRequestsToPresentationResearchAndImageTools() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse noToolResponse = new ChatResponse(List.of(
                new Generation(new AssistantMessage("พร้อมสร้างไฟล์"))));
        ChatResponse structuredResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("{\"spec\":{}}"))));
        ChatResponse reviewedResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"spec\":{\"slides\":[{\"layout\":\"editorial\",\"body\":\"เลือกโค้ดแล้วขอคำอธิบาย\"}]}}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(noToolResponse, structuredResponse, reviewedResponse, noToolResponse);
        List<Tool> tools = List.of(new CalculatorAddTool(), namedTool("presentation.create"),
                namedTool("image.generate"), namedTool("web.search"), namedTool("web.open_url"));
        var registry = new DefaultToolRegistry(tools);
        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                tools, new DefaultToolExecutor(registry), new ObjectMapper());

        String brief = "สร้าง 8 สไลด์พร้อมข้อจำกัดว่าคำตอบอาจผิด จึงต้องอ่านโค้ดและรันเทสต์";
        runtime.call(new Prompt(List.of(new SystemMessage("MINIKUN_PRESENTATION_CREATE_REQUIRED"),
                        new UserMessage(brief)),
                        OllamaChatOptions.builder().model("main-model").build()),
                new ConversationId("conversation"));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(4)).call(prompt.capture());
        for (Prompt attemptedPrompt : List.of(prompt.getAllValues().get(0), prompt.getAllValues().get(3))) {
            List<String> offeredTools = ((ToolCallingChatOptions) attemptedPrompt.getOptions())
                    .getToolCallbacks().stream().map(callback -> callback.getToolDefinition().name()).toList();
            assertEquals(List.of("image.generate", "presentation.create", "web.open_url", "web.search"), offeredTools);
        }
        assertEquals(true, prompt.getAllValues().get(1).getInstructions().stream()
                .filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                .anyMatch(message -> message.getText().contains("has not been created yet")));
        var structuredOptions = (OllamaChatOptions) prompt.getAllValues().get(1).getOptions();
        assertEquals(List.of(), structuredOptions.getToolCallbacks());
        assertEquals(4096, structuredOptions.getMaxTokens());
        assertEquals("object", ((Map<?, ?>) structuredOptions.getFormat()).get("type"));
        var repairItems = new ObjectMapper().valueToTree(structuredOptions.getFormat())
                .path("properties").path("spec").path("properties").path("slides").path("items");
        assertEquals(List.of("cover", "editorial"), new ObjectMapper().convertValue(
                repairItems.path("properties").path("layout").path("enum"), List.class));
        assertEquals(false, repairItems.path("properties").has("bullets"));
        assertEquals(true, repairItems.path("required").toString().contains("body"));
        assertEquals(structuredOptions.getFormat(), ((OllamaChatOptions) prompt.getAllValues().get(2).getOptions()).getFormat());
        assertEquals(true, prompt.getAllValues().get(2).getInstructions().stream()
                .anyMatch(message -> message.getText().contains("Review and revise this draft")));
        var reviewInstructions = prompt.getAllValues().get(2).getInstructions();
        assertEquals(UserMessage.class, reviewInstructions.getLast().getClass());
        assertEquals(true, reviewInstructions.getLast().getText().startsWith("ตรวจแก้ร่าง JSON"));
        assertEquals(true, reviewInstructions.getLast().getText().contains(brief));
        assertEquals(true, prompt.getAllValues().get(3).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .anyMatch(result -> result.name().equals("presentation.create")
                        && result.responseData().contains("\"success\":true")
                        && result.responseData().contains("เลือกโค้ดแล้วขอคำอธิบาย")));
    }

    @Test
    void retriesPresentationWhenTheFirstToolCallFails() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse failedPresentationCall = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(
                        "presentation-call", "function", "presentation.create", "{\"spec_json\":\"{}\"}")))
                        .build())));
        var structuredResponse = new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"spec_json\":\"{}\"}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(
                failedPresentationCall, structuredResponse,
                structuredResponse, structuredResponse, structuredResponse, structuredResponse);
        Tool presentation = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("presentation.create", "create a PowerPoint", Map.of(
                        "spec_json", new ToolParameter("spec_json", ToolParameterType.STRING, true, "slide spec")));
            }
            @Override public ToolResult execute(com.minikun.tools.ToolCallContext context,
                    Map<String, Object> arguments) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "invalid presentation spec");
            }
        };
        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                List.of(presentation), new DefaultToolExecutor(new DefaultToolRegistry(List.of(presentation))),
                new ObjectMapper());

        runtime.call(new Prompt(List.of(new SystemMessage("MINIKUN_PRESENTATION_CREATE_REQUIRED")),
                        OllamaChatOptions.builder().model("main-model").build()),
                new ConversationId("conversation"));

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(6)).call(prompts.capture());
        assertEquals(true, prompts.getAllValues().get(2).getInstructions().stream()
                .filter(SystemMessage.class::isInstance).map(SystemMessage.class::cast)
                .anyMatch(message -> message.getText().contains("has not been created yet")
                        && message.getText().contains("nested spec object")));
        assertEquals(false, prompts.getAllValues().get(2).getInstructions().stream()
                .anyMatch(message -> message instanceof AssistantMessage || message instanceof ToolResponseMessage));
        assertEquals(true, prompts.getAllValues().get(2).getInstructions().stream()
                .anyMatch(message -> message.getText().contains("invalid presentation spec")));
    }

    @Test
    void reviewsNativePresentationBeforeSavingWithoutChangingOtherToolCalls() {
        ChatModel chatModel = mock(ChatModel.class);
        String draft = "{\"spec\":{\"slides\":[{\"body\":\"ติดตั้งส่วนขยาย\"}]}}";
        String reviewed = "{\"spec\":{\"slides\":[{\"layout\":\"editorial\","
                + "\"body\":\"ติดตั้งส่วนขยาย แล้วอ่านโค้ดและรันเทสต์ เพราะคำตอบอาจผิด\",\"speakerNotes\":\"SOURCE_NOTE\"}]}}";
        ChatResponse nativeCalls = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("deck", "function", "presentation.create", draft),
                        new AssistantMessage.ToolCall("search", "function", "web.search", "{\"query\":\"Copilot\"}")))
                .build())));
        when(chatModel.call(any(Prompt.class))).thenReturn(nativeCalls,
                new ChatResponse(List.of(new Generation(new AssistantMessage(reviewed)))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("แนบไฟล์แล้ว")))));
        List<Tool> tools = List.of(namedTool("presentation.create"), namedTool("web.search"));
        var runtime = new SpringAiToolCallingRuntime(new DefaultActiveChatModelProvider(
                new ActiveModelConfiguration(ChatModelId.EXISTING),
                new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                tools, new DefaultToolExecutor(new DefaultToolRegistry(tools)), new ObjectMapper());
        var evidence = ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(
                "source", "web.open_url", "SOURCE_EVIDENCE: use the selected or attached code"))).build();
        runtime.call(new Prompt(List.of(new SystemMessage("MINIKUN_PRESENTATION_CREATE_REQUIRED"),
                new UserMessage("สร้างสไลด์พร้อมข้อจำกัดและขั้นตอนตรวจคำตอบ"), evidence)),
                new ConversationId("conversation"));

        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(3)).call(prompts.capture());
        assertEquals("json", ((OllamaChatOptions) prompts.getAllValues().get(1).getOptions()).getFormat());
        assertEquals(true, prompts.getAllValues().get(1).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .anyMatch(result -> result.responseData().contains("SOURCE_EVIDENCE")));
        var results = prompts.getAllValues().getLast().getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream()).toList();
        assertEquals(true, results.stream().anyMatch(result -> result.id().equals("deck")
                && result.responseData().contains("คำตอบอาจผิด")));
        assertEquals(true, results.stream().anyMatch(result -> result.id().equals("deck")
                && result.responseData().contains("SOURCE_NOTE")
                && result.responseData().contains("\"layout\":\"editorial\"")));
        assertEquals(true, results.stream().anyMatch(result -> result.id().equals("search")
                && result.responseData().contains("\"query\":\"Copilot\"")));
    }

    @Test
    void executesNativeToolCallAndReturnsFinalAssistantResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse toolRequest = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "calculator.add", "{\"a\":2,\"b\":3}")))
                        .build())));
        ChatResponse finalResponse = new ChatResponse(List.of(new Generation(
                new AssistantMessage("The answer is 5."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(toolRequest, finalResponse);

        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                List.of(new CalculatorAddTool()),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Add 2 and 3.",
                OllamaChatOptions.builder().model("main-model").disableThinking().numCtx(16_384).build()),
                new ConversationId("conversation"));

        assertEquals("The answer is 5.", response.getResult().getOutput().getText());
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(prompts.capture());
        assertEquals(true, prompts.getAllValues().stream()
                .allMatch(prompt -> prompt.getOptions() instanceof OllamaChatOptions));
        assertEquals(true, prompts.getAllValues().stream()
                .map(prompt -> (OllamaChatOptions) prompt.getOptions())
                .allMatch(options -> Boolean.FALSE.equals(options.getThinkOption().toJsonValue())));
        assertEquals(true, prompts.getAllValues().stream()
                .map(prompt -> (OllamaChatOptions) prompt.getOptions())
                .allMatch(options -> Integer.valueOf(16_384).equals(options.getNumCtx())));
        assertEquals(true, prompts.getAllValues().stream()
                .map(prompt -> (OllamaChatOptions) prompt.getOptions())
                .allMatch(options -> "main-model".equals(options.getModel())));
        String continuation = prompts.getAllValues().get(1).getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .flatMap(message -> message.getResponses().stream())
                .map(ToolResponseMessage.ToolResponse::responseData)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        assertEquals(true, continuation.contains("assistant_instruction"));
    }

    @Test
    void supportsMultipleToolContinuationsUntilFinalAssistantResponse() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse firstToolRequest = toolRequest("call-1", 2, 3);
        ChatResponse secondToolRequest = toolRequest("call-2", 4, 6);
        ChatResponse finalResponse = new ChatResponse(List.of(new Generation(
                new AssistantMessage("The verified results are 5 and 10."))));
        when(chatModel.call(any(Prompt.class))).thenReturn(firstToolRequest, secondToolRequest, finalResponse);

        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                List.of(new CalculatorAddTool()),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Calculate both sums."), new ConversationId("conversation"));

        assertEquals("The verified results are 5 and 10.", response.getResult().getOutput().getText());
        verify(chatModel, times(3)).call(any(Prompt.class));
    }

    @Test
    void returnsSafeResponseWhenToolContinuationLimitIsReached() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse[] toolRequests = new ChatResponse[5];
        for (int index = 0; index < toolRequests.length; index++) {
            toolRequests[index] = toolRequest("call-" + index, index, 1);
        }
        when(chatModel.call(any(Prompt.class))).thenReturn(toolRequests[0], toolRequests[1], toolRequests[2],
                toolRequests[3], toolRequests[4]);

        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(chatModel)))),
                List.of(new CalculatorAddTool()),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Keep calculating."), new ConversationId("conversation"));

        assertEquals(true, response.getResult().getOutput().getText().contains("ยังไม่เสร็จสมบูรณ์"));
        verify(chatModel, times(5)).call(any(Prompt.class));
    }

    @Test
    void returnsDeterministicConfirmationMessageBeforeFinalModelTurn() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse toolRequest = new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "confirm-call", "function", "confirmation.tool", "{}")))
                        .build())));
        when(chatModel.call(any(Prompt.class))).thenReturn(toolRequest,
                new ChatResponse(List.of(new Generation(new AssistantMessage("model should not answer")))));
        Tool confirmationTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("confirmation.tool", "create a pending confirmation", Map.of());
            }

            @Override
            public com.minikun.tools.ToolResult execute(
                    com.minikun.tools.ToolCallContext context, Map<String, Object> arguments) {
                return com.minikun.tools.ToolResult.success(Map.of(
                        "requires_confirmation", true,
                        "message", "ยังไม่ได้บันทึกครับ กรุณายืนยันก่อน"));
            }
        };
        SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                new DefaultActiveChatModelProvider(
                        new ActiveModelConfiguration(ChatModelId.EXISTING),
                        new DefaultChatModelProviderRegistry(List.of(
                                new ExistingChatModelProvider(chatModel)))),
                List.of(confirmationTool),
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(confirmationTool))),
                new ObjectMapper());

        ChatResponse response = runtime.call(new Prompt("Create something."), new ConversationId("conversation"));

        assertEquals("ยังไม่ได้บันทึกครับ กรุณายืนยันก่อน", response.getResult().getOutput().getText());
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    private ChatResponse toolRequest(String callId, int a, int b) {
        return new ChatResponse(List.of(new Generation(
                AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                callId, "function", "calculator.add", "{\"a\":" + a + ",\"b\":" + b + "}")))
                        .build())));
    }

    private Tool namedTool(String name) {
        return new Tool() {
            @Override public ToolDefinition definition() {
                Map<String, Object> properties = Map.of("layout", Map.of("type", "string", "enum", List.of("editorial")),
                        "body", Map.of("type", "string"), "bullets", Map.of("type", "array"));
                Map<String, Object> spec = Map.of("properties", Map.of("slides", Map.of("type", "array", "items", Map.of(
                        "oneOf", List.of(Map.of("type", "object", "properties", properties, "required", List.of("body")))))));
                return new ToolDefinition(name, "test tool", "presentation.create".equals(name)
                        ? Map.of("spec", new ToolParameter("spec", ToolParameterType.OBJECT, true, "deck", spec))
                        : "web.search".equals(name)
                        ? Map.of("query", new ToolParameter("query", ToolParameterType.STRING, true, "query")) : Map.of());
            }
            @Override public ToolResult execute(com.minikun.tools.ToolCallContext context,
                    Map<String, Object> arguments) { return ToolResult.success(Map.copyOf(arguments)); }
        };
    }

    @Test
    void callbackMakesSuccessfulResultExplicitForFinalMcsAnswer() throws Exception {
        CalculatorAddTool tool = new CalculatorAddTool();
        SpringAiToolCallback callback = new SpringAiToolCallback(
                tool,
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))),
                new ObjectMapper());

        var result = new ObjectMapper().readTree(callback.call("{\"a\":2,\"b\":3}"));

        assertEquals(true, result.get("success").asBoolean());
        assertEquals("calculator.add", result.get("tool").asText());
        assertEquals("5", result.get("result").asText());
        assertEquals(true, result.get("assistant_instruction").asText().contains("verified result"));
        assertEquals(true, result.get("assistant_instruction").asText().contains("MCS"));
    }

    @Test
    void callbackExposesDomainDefinitionAsJsonSchema() throws Exception {
        CalculatorAddTool tool = new CalculatorAddTool();
        SpringAiToolCallback callback = new SpringAiToolCallback(
                tool,
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool))),
                new ObjectMapper());

        var schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());

        assertEquals("calculator.add", callback.getToolDefinition().name());
        assertEquals("number", schema.get("properties").get("a").get("type").asText());
        assertEquals("[\"a\",\"b\"]", schema.get("required").toString());
    }

    @Test
    void callbackExposesNestedObjectParametersAsJsonObjects() throws Exception {
        Tool deckTool = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("presentation.create", "Create a PowerPoint.", Map.of(
                        "spec", new ToolParameter("spec", ToolParameterType.OBJECT, true, "Nested slide deck.")));
            }
            @Override public ToolResult execute(com.minikun.tools.ToolCallContext context,
                    Map<String, Object> arguments) { return ToolResult.success(arguments); }
        };
        SpringAiToolCallback callback = new SpringAiToolCallback(deckTool,
                new DefaultToolExecutor(new DefaultToolRegistry(List.of(deckTool))), new ObjectMapper());

        var schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());

        assertEquals("object", schema.path("properties").path("spec").path("type").asText());
        assertEquals("[\"spec\"]", schema.path("required").toString());
    }

    @Test
    void highRiskCallbackPersistsConfirmationInsteadOfExecutingToolWithoutNativeGate() throws Exception {
        Tool mutatingTool = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("memory.write", "persist a local value", Map.of(
                        "value", new ToolParameter("value", ToolParameterType.STRING, true, "value")));
            }
            @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return true; }
            @Override public ToolResult execute(com.minikun.tools.ToolCallContext context,
                    Map<String, Object> arguments) { throw new AssertionError("must not execute before confirmation"); }
        };
        ToolExecutor executor = mock(ToolExecutor.class);
        PlannerConfirmationService confirmations = mock(PlannerConfirmationService.class);
        SpringAiToolCallback callback = new SpringAiToolCallback(mutatingTool, executor, new ObjectMapper(),
                AgentExecutionTracker.noop(), confirmations);

        var result = new ObjectMapper().readTree(callback.call("{\"value\":\"secret\"}", new ToolContext(Map.of(
                "conversationId", "conversation", "ownerId", "owner", "riskExplicitReview", true))));

        assertEquals(true, result.path("result").path("requires_confirmation").asBoolean());
        verify(executor, never()).execute(any(), any());
        verify(confirmations).save(any(), eq("owner"), eq("agent-risk.memory.write"), any());
    }

    @Test
    void highRiskCallbackCannotAcceptModelSuppliedConfirmation() throws Exception {
        Tool mutatingTool = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("native.write", "write with native confirmation", Map.of(
                        "confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false, "approval")));
            }
            @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return true; }
            @Override public ToolResult execute(com.minikun.tools.ToolCallContext context,
                    Map<String, Object> arguments) { return ToolResult.success(arguments); }
        };
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.execute(any(), any())).thenAnswer(invocation -> {
            com.minikun.tools.ToolCall call = invocation.getArgument(1);
            return ToolResult.success(call.arguments());
        });
        SpringAiToolCallback callback = new SpringAiToolCallback(mutatingTool, executor, new ObjectMapper());

        var result = new ObjectMapper().readTree(callback.call("{\"confirmed\":true}", new ToolContext(Map.of(
                "conversationId", "conversation", "ownerId", "owner", "riskExplicitReview", true))));

        assertEquals(false, result.path("result").path("confirmed").asBoolean());
    }

    @Test
    void callbackRetriesTrackedTransientToolFailureAndReturnsSuccessfulAttempt() throws Exception {
        CalculatorAddTool tool = new CalculatorAddTool();
        ToolExecutor executor = mock(ToolExecutor.class);
        AgentExecutionTracker tracker = mock(AgentExecutionTracker.class);
        UUID runId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-21T08:00:00Z");
        AgentExecutionStep first = new AgentExecutionStep(UUID.randomUUID(), runId, 1, "call-1",
                "calculator.add", "{\"a\":2,\"b\":3}", AgentStepStatus.RUNNING, 1,
                "", "", "", now, now, null);
        AgentExecutionStep second = new AgentExecutionStep(first.id(), runId, 1, "call-1",
                "calculator.add", first.argumentsJson(), AgentStepStatus.RETRYING, 2,
                "", "", "", now, now, null);
        ToolResult failure = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "temporary");
        ToolResult success = ToolResult.success(5);
        when(tracker.beginStep(any(), any(), any(), any())).thenReturn(first, second);
        when(tracker.shouldRetry(failure, 1)).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(failure, success);
        SpringAiToolCallback callback = new SpringAiToolCallback(tool, executor, new ObjectMapper(), tracker);
        callback.setCurrentCallId("call-1");

        var result = new ObjectMapper().readTree(callback.call("{\"a\":2,\"b\":3}",
                new ToolContext(Map.of("agentRunId", runId.toString(), "conversationId", "conversation",
                        "ownerId", "owner"))));

        assertEquals(true, result.get("success").asBoolean());
        assertEquals(5, result.get("result").asInt());
        verify(executor, times(2)).execute(any(), any());
        verify(tracker).finishStep(runId, "call-1", failure, true);
        verify(tracker).finishStep(runId, "call-1", success, false);
    }

    @Test
    void callbackNeverRetriesAMutatingTool() throws Exception {
        Tool mutatingTool = new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition("native.write", "mutating operation", Map.of());
            }
            @Override public ToolResult execute(com.minikun.tools.ToolCallContext context,
                    Map<String, Object> arguments) { return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "uncertain"); }
        };
        ToolExecutor executor = mock(ToolExecutor.class);
        AgentExecutionTracker tracker = mock(AgentExecutionTracker.class);
        UUID runId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-21T08:00:00Z");
        AgentExecutionStep step = new AgentExecutionStep(UUID.randomUUID(), runId, 1, "call-1",
                "native.write", "{}", AgentStepStatus.RUNNING, 1, "", "", "", now, now, null);
        ToolResult failure = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "uncertain");
        when(tracker.beginStep(any(), any(), any(), any())).thenReturn(step);
        when(tracker.shouldRetry(failure, 1)).thenReturn(true);
        when(executor.execute(any(), any())).thenReturn(failure);
        SpringAiToolCallback callback = new SpringAiToolCallback(mutatingTool, executor, new ObjectMapper(), tracker);
        callback.setCurrentCallId("call-1");

        var result = new ObjectMapper().readTree(callback.call("{}", new ToolContext(Map.of(
                "agentRunId", runId.toString(), "conversationId", "conversation", "ownerId", "owner"))));

        assertEquals(false, result.path("success").asBoolean());
        verify(executor, times(1)).execute(any(), any());
        verify(tracker).finishStep(runId, "call-1", failure, false);
        verify(tracker, never()).shouldRetry(any(), any(Integer.class));
    }

        @Test
        void rejectsToolCallingWhenActiveProviderDoesNotSupportIt() {
                ChatModelProvider provider = new ChatModelProvider() {
                        @Override
                        public ChatModelId id() {
                                return ChatModelId.EXISTING;
                        }

                        @Override
                        public ModelCapabilities capabilities() {
                                return new ModelCapabilities(true, false, false);
                        }

                        @Override
                        public ChatResponse chat(Prompt prompt) {
                                throw new AssertionError("Provider must not be invoked for unsupported tools");
                        }

                        @Override
                        public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                                throw new AssertionError("Provider must not stream tool calls");
                        }
                };
                SpringAiToolCallingRuntime runtime = new SpringAiToolCallingRuntime(
                                new DefaultActiveChatModelProvider(
                                                ActiveModelConfiguration.parse("existing"),
                                                new DefaultChatModelProviderRegistry(List.of(provider))),
                                List.of(new CalculatorAddTool()),
                                new DefaultToolExecutor(new DefaultToolRegistry(List.of(new CalculatorAddTool()))),
                                new ObjectMapper());

                IllegalStateException exception = assertThrows(
                                IllegalStateException.class,
                                () -> runtime.call(new Prompt("Add 2 and 3."), new ConversationId("conversation")));

                assertEquals(true, exception.getMessage().contains("EXISTING"));
        }
}
