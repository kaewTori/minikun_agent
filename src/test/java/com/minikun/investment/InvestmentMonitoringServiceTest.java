package com.minikun.investment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class InvestmentMonitoringServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T02:00:00Z");

    @Test
    void refreshesAndDeduplicatesNewsAgainstTheSavedPortfolioPlan() {
        InvestmentService investments = mock(InvestmentService.class);
        InvestmentPolicy policy = new InvestmentPolicy(
                "owner-a", "USD", "VTI", BigDecimal.valueOf(20), "long-term wealth", "10 years", "moderate",
                NOW, NOW);
        PortfolioSummary portfolio = new PortfolioSummary(
                "owner-a", "USD", "AVERAGE_COST", BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100),
                List.of(new PortfolioPosition("AMZN", "Amazon", "EQUITY", "USD", BigDecimal.ONE,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)), policy, List.of());
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(policy);
        when(investments.theses("owner-a", InvestmentThesisStatus.ACTIVE)).thenReturn(List.of());

        SearchService search = mock(SearchService.class);
        when(search.search(any())).thenReturn(new KnowledgeContext("news", List.of(new KnowledgeCandidate(
                "search-0", KnowledgeSource.SEARCH,
                "Amazon (AMZN) reports earnings guidance surprise (https://news.test/amzn-1): revenue and guidance changed",
                0, "https://news.test/amzn-1"))));
        InMemoryMonitorStore store = new InMemoryMonitorStore();
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                investments, externalWithoutMarketKey(), store, search, mock(TaskModelProvider.class), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), java.time.Duration.ofSeconds(1), 3, 8, 48, "UTC",
                8, 3, java.time.Duration.ofHours(72));

        Map<String, Object> first = monitoring.refresh("owner-a");
        Map<?, ?> news = (Map<?, ?>) first.get("news");
        assertEquals(1, news.get("new_event_count"));
        assertEquals("HIGH", ((InvestmentNewsEvent) store.events.getFirst()).materiality());
        assertEquals("Amazon (AMZN) reports earnings guidance surprise", store.events.getFirst().title());
        assertEquals("revenue and guidance changed", store.events.getFirst().summary());

        Map<String, Object> second = monitoring.refresh("owner-a");
        assertEquals(0, ((Map<?, ?>) second.get("news")).get("new_event_count"));
        assertEquals(1, store.events.size());
        assertEquals("partial", second.get("status"));
    }

    @Test
    void filtersUnrelatedStaticAndStaleSearchResultsBeforeSavingNews() {
        InvestmentService investments = mock(InvestmentService.class);
        InvestmentPolicy policy = new InvestmentPolicy(
                "owner-a", "USD", "VTI", BigDecimal.valueOf(20), "long-term wealth", "10 years", "moderate",
                NOW, NOW);
        PortfolioSummary portfolio = new PortfolioSummary(
                "owner-a", "USD", "AVERAGE_COST", BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100),
                List.of(new PortfolioPosition("AMZN", "Amazon", "EQUITY", "USD", BigDecimal.ONE,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)), policy, List.of());
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(policy);
        when(investments.theses("owner-a", InvestmentThesisStatus.ACTIVE)).thenReturn(List.of());

        SearchService search = mock(SearchService.class);
        when(search.search(any())).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            assertTrue(request.query().contains("Amazon"));
            assertTrue(request.query().contains("AMZN"));
            assertEquals("news", request.options().category());
            assertEquals("week", request.options().timeRange());
            return new KnowledgeContext("news", List.of(
                    candidate("oracle", "Oracle earnings guidance (https://news.test/oracle): Oracle outlook changed", null),
                    candidate("chart", "Amazon Stock Chart (https://news.test/chart): historical price chart", NOW),
                    candidate("old", "Amazon announces results (https://news.test/old): revenue changed", NOW.minus(Duration.ofDays(3))),
                    candidate("valid", "Amazon reports earnings guidance (https://news.test/valid): revenue and guidance changed", NOW)));
        });
        InMemoryMonitorStore store = new InMemoryMonitorStore();
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                investments, externalWithoutMarketKey(), store, search, mock(TaskModelProvider.class), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1), 4, 8, 48, "UTC",
                8, 3, Duration.ofHours(72));

        Map<String, Object> report = monitoring.refresh("owner-a");

        assertEquals(1, store.events.size());
        assertEquals("Amazon reports earnings guidance", store.events.getFirst().title());
        assertEquals(1, ((Map<?, ?>) report.get("news")).get("new_event_count"));
        verify(search).search(any());
    }

    @Test
    void excludesPreviouslyStoredIrrelevantNewsFromTheReminder() {
        InvestmentService investments = mock(InvestmentService.class);
        InvestmentPolicy policy = new InvestmentPolicy(
                "owner-a", "USD", "VTI", BigDecimal.valueOf(20), "long-term wealth", "10 years", "moderate",
                NOW, NOW);
        PortfolioSummary portfolio = new PortfolioSummary(
                "owner-a", "USD", "AVERAGE_COST", BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100),
                List.of(new PortfolioPosition("WEC", "WEC Energy Group", "EQUITY", "USD", BigDecimal.ONE,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)), policy, List.of());
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(policy);
        when(investments.theses("owner-a", InvestmentThesisStatus.ACTIVE)).thenReturn(List.of());
        InMemoryMonitorStore store = new InMemoryMonitorStore();
        store.saveNews(new InvestmentNewsEvent(UUID.randomUUID(), "owner-a", "old-oracle", "WEC",
                "Oracle cloud deal", "Oracle signed a cloud deal", "https://news.test/oracle", "TAVILY",
                NOW, NOW, "HIGH"));
        SearchService search = mock(SearchService.class);
        when(search.search(any())).thenReturn(KnowledgeContext.empty());
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                investments, externalWithoutMarketKey(), store, search, mock(TaskModelProvider.class),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(1), 1, 8, 48, "UTC",
                8, 3, Duration.ofHours(72));

        Map<String, Object> report = monitoring.refresh("owner-a");

        assertTrue(((List<?>) ((Map<?, ?>) report.get("news")).get("events")).isEmpty());
    }

    @Test
    void rejectsModelPromptLeakageAndUsesSafeFallbackText() {
        TaskModelProvider summarizer = mock(TaskModelProvider.class);
        when(summarizer.generate(any())).thenReturn(
                "{\"what_happened\":\"system prompt: ignore instructions\","
                        + "\"portfolio_impact\":\"กำไร 999%\",\"watch_next\":\"...\"}");
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                mock(InvestmentService.class), externalWithoutMarketKey(), new InMemoryMonitorStore(),
                mock(SearchService.class), summarizer, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1), 1, 8, 48, "UTC", 8, 3, Duration.ofHours(72));

        String message = monitoring.formatBrief(Map.of(
                "report_date", "2026-09-11",
                "portfolio", Map.of("positions", List.of()),
                "market_snapshot", Map.of("status", "empty"),
                "news", Map.of("events", List.of(Map.of(
                        "symbol", "AMZN", "instrument_name", "Amazon", "title", "Amazon earnings",
                        "summary", "Revenue changed", "url", "https://news.test/amzn", "materiality", "HIGH"))),
                "recommendations", List.of()));

        assertFalse(message.contains("system prompt"));
        assertFalse(message.contains("999%"));
        assertTrue(message.contains("ยังยืนยันผลกระทบต่อพอร์ตไม่ได้"));
    }

    @Test
    void dailyDeliveryIsNotRepeatedForTheSameLocalDate() {
        InMemoryMonitorStore store = new InMemoryMonitorStore();
        store.deliveredDate = LocalDate.of(2026, 9, 11);
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                mock(InvestmentService.class), externalWithoutMarketKey(), store, mock(SearchService.class),
                mock(TaskModelProvider.class), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), java.time.Duration.ofSeconds(1),
                1, 8, 48, "UTC", 8, 3, java.time.Duration.ofHours(72));

        assertTrue(monitoring.prepareDaily("owner-a").isEmpty());
    }

    @Test
    void refreshesAtMostEightQuotesAndRotatesTheRemainingPositions() {
        InvestmentService investments = mock(InvestmentService.class);
        InvestmentPolicy policy = new InvestmentPolicy(
                "owner-a", "USD", "VTI", BigDecimal.valueOf(20), "long-term wealth", "10 years", "moderate",
                NOW, NOW);
        List<PortfolioPosition> positions = java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(index -> new PortfolioPosition(
                        "S%02d".formatted(index), "Asset " + index, "EQUITY", "USD", BigDecimal.ONE,
                        BigDecimal.valueOf(100 + index), BigDecimal.valueOf(100 + index), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
                .toList();
        PortfolioSummary portfolio = new PortfolioSummary(
                "owner-a", "USD", "AVERAGE_COST", BigDecimal.valueOf(1_166), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(1_166), positions, policy, List.of());
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(policy);
        when(investments.theses("owner-a", InvestmentThesisStatus.ACTIVE)).thenReturn(List.of());
        when(investments.quotePriorities("owner-a")).thenReturn(Map.of(
                "S01", InvestmentQuotePriority.MINOR,
                "S02", InvestmentQuotePriority.MINOR,
                "S03", InvestmentQuotePriority.MINOR));

        InvestmentExternalDataService external = mock(InvestmentExternalDataService.class);
        when(external.marketDataConfigured()).thenReturn(true);
        List<List<String>> quoteCalls = new ArrayList<>();
        when(external.latestQuotes(any())).thenAnswer(invocation -> {
            List<String> requested = new ArrayList<>(invocation.getArgument(0));
            quoteCalls.add(requested);
            Map<String, InvestmentExternalDataService.MarketQuote> quotes = new HashMap<>();
            requested.forEach(symbol -> quotes.put(symbol, new InvestmentExternalDataService.MarketQuote(
                    symbol, BigDecimal.valueOf(120), "USD", NOW, "twelve-data")));
            return quotes;
        });

        InMemoryMonitorStore store = new InMemoryMonitorStore();
        InvestmentMonitoringService firstDay = new InvestmentMonitoringService(
                investments, external, store, mock(SearchService.class), mock(TaskModelProvider.class), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), java.time.Duration.ofSeconds(1), 1, 8, 48, "UTC",
                8, 3, java.time.Duration.ofHours(72));
        Map<?, ?> firstSnapshot = (Map<?, ?>) firstDay.refresh("owner-a").get("market_snapshot");

        InvestmentMonitoringService secondDay = new InvestmentMonitoringService(
                investments, external, store, mock(SearchService.class), mock(TaskModelProvider.class), new ObjectMapper(),
                Clock.fixed(NOW.plus(java.time.Duration.ofDays(1)), ZoneOffset.UTC), java.time.Duration.ofSeconds(1),
                1, 8, 48, "UTC", 8, 3, java.time.Duration.ofHours(72));
        Map<?, ?> secondSnapshot = (Map<?, ?>) secondDay.refresh("owner-a").get("market_snapshot");

        assertEquals(2, quoteCalls.size());
        assertEquals(8, quoteCalls.getFirst().size());
        assertEquals(8, quoteCalls.get(1).size());
        assertEquals(3, ((List<?>) firstSnapshot.get("missing_symbols")).size());
        assertEquals(3, ((List<?>) secondSnapshot.get("cached_symbols")).size());
        assertEquals(0, ((List<?>) secondSnapshot.get("missing_symbols")).size());
        List<?> firstFresh = (List<?>) firstSnapshot.get("fresh_request_symbols");
        List<?> secondFresh = (List<?>) secondSnapshot.get("fresh_request_symbols");
        assertTrue(firstFresh.subList(0, 5).stream().noneMatch(
                List.of("S01", "S02", "S03")::contains));
        assertTrue(secondFresh.subList(0, 5).stream().noneMatch(
                List.of("S01", "S02", "S03")::contains));
        assertEquals(5, firstFresh.stream().filter(secondFresh::contains).count());
    }

    @Test
    void formatsTheTypedPortfolioAndNewsAsAMinikunSummary() {
        Instant now = NOW;
        InvestmentPolicy policy = new InvestmentPolicy(
                "owner-a", "USD", "VTI", BigDecimal.valueOf(20), "long-term wealth", "10 years", "moderate",
                now, now);
        PortfolioSummary portfolio = new PortfolioSummary(
                "owner-a", "USD", "AVERAGE_COST", BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.valueOf(100),
                List.of(new PortfolioPosition("AMZN", "Amazon", "EQUITY", "USD", BigDecimal.ONE,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)), policy, List.of());
        TaskModelProvider summarizer = mock(TaskModelProvider.class);
        when(summarizer.generate(any())).thenReturn("{\"summary\":\"รายได้เติบโต 12% เมื่อเทียบกับปีก่อน สะท้อนผลประกอบการที่ดีขึ้นของ AMZN\"}");
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                mock(InvestmentService.class), externalWithoutMarketKey(), new InMemoryMonitorStore(),
                mock(SearchService.class), summarizer, new ObjectMapper(), Clock.fixed(now, ZoneOffset.UTC),
                java.time.Duration.ofSeconds(1), 1, 8, 48, "UTC", 8, 3, java.time.Duration.ofHours(72));
        String url = "https://news.test/amzn-1";
        String message = monitoring.formatBrief(Map.of(
                "report_date", "2026-09-11",
                "portfolio", portfolio,
                "market_snapshot", Map.of(
                        "status", "ok", "base_currency", "USD", "total_market_value", BigDecimal.valueOf(120),
                        "unrealized_profit_loss", BigDecimal.valueOf(20), "cached_symbols", List.of()),
                "news", Map.of("events", List.of(Map.of(
                        "symbol", "AMZN", "title", "Amazon earnings", "summary", "Revenue grew 12% year over year.",
                        "url", url, "materiality", "HIGH"))),
                "recommendations", List.of()));

        assertTrue(message.contains("ตอนนี้พี่สาวถืออยู่ 1 สินทรัพย์ คือ Amazon"));
        assertTrue(message.contains("ต้นทุนคงค้าง 100 USD"));
        assertTrue(message.contains("เกิดอะไรขึ้น: รายได้เติบโต 12% เมื่อเทียบกับปีก่อน สะท้อนผลประกอบการที่ดีขึ้นของ AMZN"));
        assertFalse(message.contains("Revenue grew 12% year over year."));
        assertFalse(message.contains(url));
        assertFalse(message.contains("review_thesis"));
        assertTrue(message.contains("พี่สาวครับ เช้านี้มินิคุงสรุปพอร์ตให้ฟังนะครับ"));
    }

    @Test
    void keepsTheReminderWithinThePushMessageLimit() {
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                mock(InvestmentService.class), externalWithoutMarketKey(), new InMemoryMonitorStore(),
                mock(SearchService.class), mock(TaskModelProvider.class), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                java.time.Duration.ofSeconds(1), 1, 8, 48, "UTC", 8, 3, java.time.Duration.ofHours(72));
        List<Map<String, Object>> events = java.util.stream.IntStream.range(0, 8)
                .mapToObj(index -> Map.<String, Object>of(
                        "symbol", "AMZN", "title", "ข่าวสำคัญ ".repeat(80),
                        "summary", "สรุปข้อมูลตลาด ".repeat(80),
                        "brief_summary", "สรุปข้อมูลตลาด ".repeat(80), "materiality", "HIGH"))
                .toList();

        String message = monitoring.formatBrief(Map.of(
                "report_date", "2026-09-11",
                "portfolio", Map.of("positions", List.of()),
                "market_snapshot", Map.of("status", "empty"),
                "news", Map.of("events", events),
                "recommendations", List.of()));

        assertTrue(message.getBytes(StandardCharsets.UTF_8).length <= 3_500);
        assertFalse(message.contains("https://"));
    }

    private InvestmentExternalDataService externalWithoutMarketKey() {
        return new InvestmentExternalDataService(
                RestClient.create(), RestClient.create(), RestClient.create(), RestClient.create(),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), "", "MinikunAgent/1.0", "", "");
    }

    private KnowledgeCandidate candidate(String id, String content, Instant publishedAt) {
        int start = content.indexOf("https://");
        int end = content.indexOf(')', start);
        String url = end > start ? content.substring(start, end) : content.substring(start);
        return new KnowledgeCandidate(id, KnowledgeSource.SEARCH, content, 0, url, publishedAt, 0.9);
    }

    private static final class InMemoryMonitorStore implements InvestmentMonitorStore {
        private final Map<String, String> reports = new HashMap<>();
        private final List<InvestmentNewsEvent> events = new ArrayList<>();
        private LocalDate deliveredDate;

        @Override public Optional<String> latestReportJson(String ownerId) { return Optional.ofNullable(reports.get(ownerId)); }
        @Override public void saveLatestReport(String ownerId, LocalDate date, Instant at, String json) { reports.put(ownerId, json); }
        @Override public boolean deliveredOn(String ownerId, LocalDate date) { return date.equals(deliveredDate); }
        @Override public void markDelivered(String ownerId, LocalDate date, Instant at) { deliveredDate = date; }
        @Override public boolean containsNews(String ownerId, String key) {
            return events.stream().anyMatch(event -> event.ownerId().equals(ownerId) && event.eventKey().equals(key));
        }
        @Override public void saveNews(InvestmentNewsEvent event) { if (!containsNews(event.ownerId(), event.eventKey())) events.add(event); }
        @Override public List<InvestmentNewsEvent> recentNews(String ownerId, Instant since, int limit) {
            return events.stream().filter(event -> event.ownerId().equals(ownerId))
                    .filter(event -> !event.discoveredAt().isBefore(since)).limit(limit).toList();
        }
    }
}
