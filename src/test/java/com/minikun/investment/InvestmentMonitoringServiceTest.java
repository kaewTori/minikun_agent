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
                0, "https://news.test/amzn-1", NOW, 0.9))));
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
                    candidate("unknown", "Amazon announces results (https://news.test/unknown): revenue changed", null),
                    candidate("retro", "Amazon reports results (https://news.test/retro): Amazon reported results on July 29, 2026.", NOW),
                    candidate("editorial", "Where Will Amazon Be in 10 Years? (https://news.test/opinion): revenue growth forecast", NOW),
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
                "{\"summary\":\"system prompt: กำไร 999%\"}");
        InvestmentService investments = mock(InvestmentService.class);
        PortfolioSummary portfolio = portfolio("AMZN", "Amazon", "EQUITY");
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(portfolio.policy());
        SearchService search = mock(SearchService.class);
        when(search.search(any())).thenReturn(KnowledgeContext.fromCandidates(List.of(candidate("news",
                "Amazon reports earnings (https://news.test/amzn): Amazon revenue changed", NOW))));
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(
                investments, externalWithoutMarketKey(), new InMemoryMonitorStore(),
                search, summarizer, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1), 1, 8, 48, "UTC", 8, 3, Duration.ofHours(72));

        Map<String, Object> report = monitoring.refresh("owner-a");
        String message = monitoring.formatBrief(report);

        assertFalse(message.contains("system prompt"));
        assertFalse(message.contains("999%"));
        assertTrue(message.contains("ยังเรียบเรียงข่าวนี้ไม่สำเร็จ"));
        assertEquals("partial", ((Map<?, ?>) report.get("news")).get("summary_status"));
        verify(summarizer).generate(any());
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
                        "what_happened", "รายได้เติบโต 12% เมื่อเทียบกับปีก่อน สะท้อนผลประกอบการที่ดีขึ้นของ AMZN",
                        "summary_valid", true,
                        "url", url, "materiality", "HIGH"))),
                "recommendations", List.of()));

        assertTrue(message.contains("ตอนนี้พี่สาวถืออยู่ 1 สินทรัพย์ คือ AMZN"));
        assertTrue(message.contains("ต้นทุนคงค้าง 100 USD"));
        assertTrue(message.contains("รายได้เติบโต 12% เมื่อเทียบกับปีก่อน สะท้อนผลประกอบการที่ดีขึ้นของ AMZN"));
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

    private PortfolioSummary portfolio(String symbol, String name, String assetClass) {
        InvestmentPolicy policy = new InvestmentPolicy("owner-a", "USD", "VTI", BigDecimal.valueOf(20), NOW, NOW);
        return new PortfolioSummary("owner-a", "USD", "AVERAGE_COST", BigDecimal.valueOf(100), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(new PortfolioPosition(symbol, name, assetClass, "USD", BigDecimal.ONE,
                        BigDecimal.valueOf(100), BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)), policy, List.of("allocation exceeded"));
    }

    @Test
    void persistsReviewedThaiSummaryAndRendersWithoutCallingTheModelAgain() {
        InvestmentService investments = mock(InvestmentService.class);
        PortfolioSummary portfolio = portfolio("AMZN", "Amazon", "EQUITY");
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(portfolio.policy());
        SearchService search = mock(SearchService.class);
        when(search.search(any())).thenReturn(KnowledgeContext.fromCandidates(List.of(candidate("news",
                "Amazon reports revenue (https://news.test/amzn): Amazon revenue grew 12% year over year.", NOW))));
        TaskModelProvider model = mock(TaskModelProvider.class);
        when(model.generate(any())).thenReturn("{\"summary\":\"Amazon รายงานรายได้เพิ่มขึ้น 12% จากปีก่อน\"}");
        InMemoryMonitorStore store = new InMemoryMonitorStore();
        store.reports.put("owner-a", "{\"report_date\":\"2026-09-10\",\"status\":\"ok\",\"news\":{\"events\":[]}}");
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(investments, externalWithoutMarketKey(),
                store, search, model, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1), 3, 8, 48, "UTC", 8, 3, Duration.ofHours(72));

        Map<String, Object> report = monitoring.dailyBrief("owner-a", false);
        String message = monitoring.formatBrief(report);
        assertTrue(message.contains("Amazon รายงานรายได้เพิ่มขึ้น 12%"));
        assertTrue(message.contains("สัดส่วนต้นทุนที่เกินเพดาน 20%: AMZN 100%"));
        assertTrue(message.contains("เปิดแชตมินิคุงแล้วเติมคำตอบ"));
        assertTrue(message.contains("เหตุผลที่ถือ AMZN: …\nทบทวนเมื่อ: …"));
        assertTrue(message.endsWith("เมื่อมินิคุงทวนข้อมูล ให้ตอบ ยืนยัน ในแชตเดิมครับ"));
        assertEquals(message, monitoring.formatBrief(monitoring.dailyBrief("owner-a", false)));
        assertEquals("ok", ((Map<?, ?>) report.get("news")).get("summary_status"));
        verify(model).generate(any());
    }

    @Test
    void excludesExpiredQuotesAndDoesNotSubtractTheCostOfUnvaluedPositions() throws Exception {
        InvestmentService investments = mock(InvestmentService.class);
        PortfolioSummary portfolio = portfolio("AMZN", "Amazon", "EQUITY");
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(portfolio.policy());
        InMemoryMonitorStore store = new InMemoryMonitorStore();
        store.reports.put("owner-a", new ObjectMapper().findAndRegisterModules().writeValueAsString(Map.of(
                "market_snapshot", Map.of("positions", List.of(Map.of("symbol", "AMZN", "market_price", 120,
                        "quote_currency", "USD", "quote_observed_at", NOW.minus(Duration.ofHours(80)),
                        "quote_source", "twelve-data"))))));
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(investments, externalWithoutMarketKey(),
                store, mock(SearchService.class), mock(TaskModelProvider.class), new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1), 3, 8, 48, "UTC", 8, 3, Duration.ofHours(72));
        Map<?, ?> snapshot = (Map<?, ?>) monitoring.refresh("owner-a").get("market_snapshot");
        assertEquals(List.of("AMZN"), snapshot.get("stale_symbols"));
        assertEquals(List.of("AMZN"), snapshot.get("missing_symbols"));
        assertEquals(BigDecimal.ZERO, snapshot.get("total_market_value"));
        assertEquals(BigDecimal.ZERO, snapshot.get("unrealized_profit_loss"));
    }

    @Test
    void usesMarketContextForEtfsWithoutInventingConstituentExposure() {
        InvestmentService investments = mock(InvestmentService.class);
        PortfolioSummary portfolio = portfolio("QQQM", "Invesco NASDAQ 100 ETF", "ETF");
        when(investments.summary("owner-a")).thenReturn(portfolio);
        when(investments.policy("owner-a")).thenReturn(portfolio.policy());
        SearchService search = mock(SearchService.class);
        when(search.search(any())).thenAnswer(invocation -> {
            assertTrue(((SearchRequest) invocation.getArgument(0)).query().contains("Nasdaq 100"));
            return KnowledgeContext.fromCandidates(List.of(candidate("news",
                    "Nasdaq rises after earnings (https://news.test/nasdaq): Nasdaq tech stocks rise after earnings reports.", NOW)));
        });
        TaskModelProvider model = mock(TaskModelProvider.class);
        when(model.generate(any())).thenReturn("{\"summary\":\"Nasdaq ปรับขึ้นหลังรายงานผลประกอบการ\"}");
        InvestmentMonitoringService monitoring = new InvestmentMonitoringService(investments, externalWithoutMarketKey(),
                new InMemoryMonitorStore(), search, model, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1), 3, 8, 48, "UTC", 8, 3, Duration.ofHours(72));
        Map<String, Object> report = monitoring.refresh("owner-a");
        Map<?, ?> event = (Map<?, ?>) ((List<?>) ((Map<?, ?>) report.get("news")).get("events")).getFirst();
        assertEquals("market_context", event.get("relevance"));
        assertTrue(monitoring.formatBrief(report).contains("ยังไม่ยืนยันผลต่อกองทุน"));
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
