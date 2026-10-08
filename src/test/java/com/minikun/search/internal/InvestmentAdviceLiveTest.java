package com.minikun.search.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.model.*;
import com.minikun.model.existing.ExistingChatModelProvider;
import com.minikun.planner.PlannerConfirmationService;
import com.minikun.search.SearchService;
import com.minikun.investment.*;
import com.minikun.search.internal.*;
import com.minikun.tools.*;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real local model, search and FX; the production database connection is read-only. */
class InvestmentAdviceLiveTest {
    public static void main(String[] args) throws Exception { new InvestmentAdviceLiveTest().run(Arrays.asList(args).contains("--fixture")); }

    @Test @EnabledIfEnvironmentVariable(named = "MINIKUN_LIVE_INVESTMENT_ADVICE", matches = "true")
    void actualPortfolioAdvice() throws Exception { run(false); }

    private void run(boolean fixture) throws Exception {
        Clock clock = Clock.systemUTC();
        var mapper = new ObjectMapper().findAndRegisterModules();
        InvestmentService investments;
        String owner = fixture ? "fixture" : "default";
        if (fixture) {
            InvestmentStore store = mock(InvestmentStore.class);
            String[][] rows = {{"AMZN","0.0063947","220.496"},{"GIL","0.6378883","54.8372"},
                    {"GRAB","0.4431103","3.498"},{"LVS","0.1893093","46.3791"},{"O","0.6207904","56.718"},
                    {"QQQM","0.1652064","266.7573"},{"SCHD","2.019107","28.8197"},{"VTI","0.0130297","289.34"},
                    {"WEC","0.2587231","109.3447"}};
            List<InvestmentTransaction> ledger = new ArrayList<>();
            for (var row : rows) ledger.add(new InvestmentTransaction(UUID.randomUUID(), owner, "fixture", "fixture",
                    InvestmentTransactionType.BUY, row[0], row[0], Set.of("VTI","SCHD","QQQM").contains(row[0]) ? "ETF" : "EQUITY",
                    "USD", new BigDecimal(row[1]), new BigDecimal(row[2]), null, BigDecimal.ZERO, clock.instant(), "", clock.instant(), null));
            when(store.listTransactions(owner)).thenReturn(ledger);
            when(store.findPolicy(owner)).thenReturn(Optional.empty());
            doThrow(new AssertionError("live advice must not write the ledger")).when(store).addTransaction(any());
            investments = new InvestmentService(store, clock, "USD");
        } else {
            String url = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/minikun");
            url += (url.contains("?") ? "&" : "?") + "options=-c%20default_transaction_read_only%3Don";
            investments = new InvestmentService(new JdbcInvestmentStore(new JdbcTemplate(new DriverManagerDataSource(url,
                    System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "minikun"), System.getenv("SPRING_DATASOURCE_PASSWORD")))), clock, "USD");
        }
        var before = investments.summary(owner);
        assertFalse(before.positions().isEmpty());
        var requests = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(15000); requests.setReadTimeout(120000);
        String key = Objects.requireNonNull(System.getenv("MINIKUN_SEARCH_TAVILY_API_KEY"), "configured search key is required");
        var search = new DefaultSearchManager(new TavilySearchProvider(RestClient.builder().requestFactory(requests)
                .baseUrl("https://api.tavily.com").defaultHeader("Authorization", "Bearer " + key).build(), mapper, clock, key, true, "basic"),
                clock, 0, new SearchDeduplicator(), new SearchBudgeter(12000), new SearchFormatter());
        var searchMemo = new HashMap<String, com.minikun.pcs.model.KnowledgeContext>();
        SearchService cachedSearch = request -> {
            var result = searchMemo.computeIfAbsent(request.query(), ignored -> search.search(request));
            try {
                Files.writeString(Path.of("target/investment-advice-" + owner + "-sources.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(searchMemo));
            } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
            return result;
        };
        var external = new InvestmentExternalDataService(RestClient.create(), RestClient.builder().requestFactory(requests)
                .baseUrl("https://api.frankfurter.dev").build(), RestClient.create(), RestClient.create(), mapper, clock, "", "MinikunAgent/1.0", "", "");
        List<Tool> tools = List.of(new InvestmentAnalyzeTool(investments),
                new InvestmentDataTool(investments, external, mock(PlannerConfirmationService.class)),
                new WebSearchTool(cachedSearch, Duration.ofSeconds(25)));
        var options = OllamaChatOptions.builder().model(System.getenv().getOrDefault("SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL",
                "hf.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF:Q6_K"))
                .numCtx(16384).numPredict(1200).temperature(0.7).disableThinking().build();
        var model = OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl("http://127.0.0.1:11434")
                .restClientBuilder(RestClient.builder().requestFactory(requests)).build()).options(options).build();
        var runtime = new SpringAiToolCallingRuntime(new DefaultActiveChatModelProvider(new ActiveModelConfiguration(ChatModelId.EXISTING),
                new DefaultChatModelProviderRegistry(List.of(new ExistingChatModelProvider(model)))), tools,
                new DefaultToolExecutor(new DefaultToolRegistry(tools)), mapper);
        List<Message> history = new ArrayList<>(List.of(new SystemMessage("MINIKUN_INVESTMENT_ADVICE_REQUIRED")));
        List<String> questions = fixture ? List.of("เรามีอยู่ 5000 บาทเอาไปเติมอะไรดีวันนี้", "เปลี่ยนใหม่เป็น 3800 บาท", "ขอยอดเป็น $ หน่อย")
                : List.of("เรามีงบอยู่ 1000 บาท เอาไปลงทุนอะไรเพิ่มดี", "เปลี่ยนใหม่เป็น 800 บาท", "ขอยอดเป็น $ หน่อย");
        StringBuilder report = new StringBuilder("Portfolio before: " + mapper.writeValueAsString(before) + "\n\n");
        for (String question : questions) {
            history.add(new UserMessage(question));
            var response = runtime.call(new Prompt(history, options), new ConversationId("advice-live-" + owner), owner);
            String answer = response.getResult().getOutput().getText();
            report.append("USER: ").append(question).append("\nANSWER: ").append(answer).append("\n\n");
            Files.writeString(Path.of("target/investment-advice-" + owner + ".txt"), report);
            Files.writeString(Path.of("target/investment-advice-" + owner + "-sources.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(searchMemo));
            assertFalse(answer.contains("ยังจัดแผนที่ตรวจ"), answer);
            assertFalse(answer.contains("เริ่มต้นธุรกิจ"), answer);
            assertTrue(answer.contains("• **"), answer);
            assertFalse(answer.contains("paper_order"), answer);
            assertTrue(answer.matches("(?s).*วันที่ \\d{4}-\\d{2}-\\d{2}.*"), "FX must retain a verifiable date: " + answer);
            if (fixture) {
                assertTrue(answer.contains("• **VTI:**"), "reference portfolio needs its underweight broad core: " + answer);
                assertFalse(answer.contains("• **QQQM:**"), "do not concentrate the large focused fund in the reference case: " + answer);
                assertFalse(answer.contains("• **SCHD:**"), "the reference case already has a large dividend allocation: " + answer);
            }
            if (question.contains("$")) assertTrue(answer.contains("รวม $"), answer);
            else assertNotNull(InvestmentAdvicePlan.previous(answer), answer);
            history.add(new AssistantMessage(answer));
        }
        var after = investments.summary(owner);
        assertEquals(before.positions(), after.positions(), "advice must not alter holdings");
        assertEquals(before.totalOpenCostBasis(), after.totalOpenCostBasis());
        assertEquals(before.policy().maxSinglePositionPercent(), after.policy().maxSinglePositionPercent());
        assertEquals(before.policy().goal(), after.policy().goal());
        System.out.println("LIVE_ADVICE_PASS owner=" + owner + " turns=" + questions.size());
    }
}
