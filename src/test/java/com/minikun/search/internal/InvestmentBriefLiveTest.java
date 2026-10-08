package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.investment.*;
import com.minikun.model.task.OllamaTaskModelProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;

/** Opt-in acceptance check: real holdings, search and model; never writes the ledger or sends a reminder. */
@EnabledIfEnvironmentVariable(named = "MINIKUN_INVESTMENT_LIVE_CHECK", matches = "true")
class InvestmentBriefLiveTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--render-saved".equals(args[0])) System.setProperty("minikun.investment.preview.render-saved", "true");
        new InvestmentBriefLiveTest().producesAReviewedBriefFromTheRunningLocalServices();
    }
    @Test
    void producesAReviewedBriefFromTheRunningLocalServices() throws Exception {
        Clock clock = Clock.systemUTC();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String owner = "default";
        String url = System.getenv().getOrDefault("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/minikun");
        url += (url.contains("?") ? "&" : "?") + "options=-c%20default_transaction_read_only%3Don";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "minikun"),
                System.getenv("SPRING_DATASOURCE_PASSWORD")));
        InvestmentService investments = new InvestmentService(new JdbcInvestmentStore(jdbc), clock, "USD");
        PreviewStore store = new PreviewStore(new JdbcInvestmentMonitorStore(jdbc).latestReportJson(owner));
        String key = System.getenv("MINIKUN_SEARCH_TAVILY_API_KEY");
        assertTrue(key != null && !key.isBlank(), "live search needs the configured Tavily key");
        key = key.strip();
        String bearer = "Bearer " + key;
        var requests = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(25)).build());
        requests.setReadTimeout(Duration.ofSeconds(90));
        TavilySearchProvider provider = new TavilySearchProvider(RestClient.builder()
                .requestFactory(requests).baseUrl("https://api.tavily.com").defaultHeader("Authorization", bearer)
                .requestInterceptor((request, body, execution) -> {
                    assertTrue(bearer.equals(request.getHeaders().getFirst("Authorization")), "search must carry configured auth");
                    return execution.execute(request, body);
                }).build(),
                mapper, clock, key, true, "basic");
        DefaultSearchManager search = new DefaultSearchManager(provider, clock, 0,
                new SearchDeduplicator(), new SearchBudgeter(12_000), new SearchFormatter());
        var model = new OllamaTaskModelProvider(RestClient.builder().requestFactory(requests)
                .baseUrl("http://127.0.0.1:11434/api/chat").build(),
                mapper, System.getenv().getOrDefault("MINIKUN_INVESTMENT_SUMMARY_MODEL",
                System.getenv().getOrDefault("SPRING_AI_OLLAMA_CHAT_OPTIONS_MODEL",
                "hf.co/llmfan46/gemma-4-E4B-it-ultra-uncensored-heretic-GGUF:Q6_K")), Duration.ofSeconds(120), true);
        var external = new InvestmentExternalDataService(RestClient.create(), RestClient.create(),
                RestClient.create(), RestClient.create(), mapper, clock, "", "MinikunAgent/1.0", "", "");
        var monitoring = new InvestmentMonitoringService(investments, external, store, search::search,
                model, mapper, clock, Duration.ofSeconds(25), 5, 8, 48, "Asia/Bangkok", 8, 3, Duration.ofHours(72));
        Map<String, Object> report;
        if (Boolean.getBoolean("minikun.investment.preview.render-saved")) {
            report = mapper.readValue(Files.readString(Path.of("target/investment-brief-preview.json")),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            report.remove("brief_text");
            if (report.get("report_date") instanceof List<?> date && date.size() == 3)
                report.put("report_date", LocalDate.of(((Number) date.get(0)).intValue(),
                        ((Number) date.get(1)).intValue(), ((Number) date.get(2)).intValue()).toString());
            report.put("brief_text", monitoring.formatBrief(report));
            store.report = Optional.of(mapper.writeValueAsString(report));
        } else report = monitoring.refresh(owner);
        String brief = monitoring.formatBrief(report);
        Files.writeString(Path.of("target/investment-brief-preview.txt"), brief);
        Files.writeString(Path.of("target/investment-brief-preview.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        Map<?, ?> news = (Map<?, ?>) report.get("news");
        List<?> events = (List<?>) news.get("events");
        assertTrue(!events.isEmpty(), "live search must find at least one dated relevant event");
        assertEquals("ok", news.get("summary_status"), "all selected stories must have reviewed Thai summaries");
        assertTrue(events.stream().map(row -> (Map<?, ?>) row).allMatch(row -> row.containsKey("published_at")));
        assertEquals(brief, monitoring.formatBrief(monitoring.dailyBrief(owner, false)));
        assertFalse(brief.contains("https://"));
        assertTrue(brief.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 3_500);
        long rendered = events.stream().map(row -> (Map<?, ?>) row).map(row -> row.get("what_happened").toString())
                .filter(text -> brief.contains(text.substring(0, Math.min(80, text.length())))).count();
        assertTrue(rendered >= Math.min(2, events.size()), "the reminder must retain at least two available story summaries");
        System.out.println("LIVE_INVESTMENT_BRIEF stories=" + events.size() + " summarized=" + news.get("summarized_count"));
    }

    private static final class PreviewStore implements InvestmentMonitorStore {
        private Optional<String> report;
        private final List<InvestmentNewsEvent> events = new ArrayList<>();
        PreviewStore(Optional<String> report) { this.report = report; }
        public Optional<String> latestReportJson(String owner) { return report; }
        public void saveLatestReport(String owner, LocalDate date, Instant at, String json) { report = Optional.of(json); }
        public boolean deliveredOn(String owner, LocalDate date) { return false; }
        public void markDelivered(String owner, LocalDate date, Instant at) { throw new AssertionError("preview cannot deliver"); }
        public boolean containsNews(String owner, String key) { return events.stream().anyMatch(e -> e.eventKey().equals(key)); }
        public void saveNews(InvestmentNewsEvent event) { events.add(event); }
        public List<InvestmentNewsEvent> recentNews(String owner, Instant since, int limit) {
            return events.stream().filter(e -> !e.discoveredAt().isBefore(since)).limit(limit).toList();
        }
    }
}
