package com.minikun.investment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.SearchService;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchRequest;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Read-only portfolio/news monitor used by the daily companion brief. */
@Service
@ConditionalOnProperty(name = "minikun.investment.enabled", havingValue = "true", matchIfMissing = true)
public final class InvestmentMonitoringService {
    private static final Logger LOGGER = LoggerFactory.getLogger(InvestmentMonitoringService.class);
    private static final int MAX_REMINDER_BYTES = 3_500;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private static final MathContext MATH = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern HIGH_MATERIALITY = Pattern.compile(
            "(?iu)(earnings|guidance|merger|acquisition|bankruptcy|fraud|lawsuit|regulator|restatement|"
                    + "dividend\s+cut|secondary\s+offering|offering|10-k|10-q|8-k|ผลประกอบการ|งบการเงิน|"
                    + "คาดการณ์|ควบรวม|ซื้อกิจการ|ล้มละลาย|ฟ้องร้อง|ก.ล.ต.|ปันผลลด|เพิ่มทุน)");
    private static final Pattern MEDIUM_MATERIALITY = Pattern.compile(
            "(?iu)(upgrade|downgrade|price target|analyst|product launch|layoff|outlook|forecast|"
                    + "อัปเกรด|ดาวน์เกรด|ราคาเป้าหมาย|นักวิเคราะห์|เปิดตัว|ปลดพนักงาน|แนวโน้ม|คาดการณ์)");

    private final InvestmentService investments;
    private final InvestmentExternalDataService external;
    private final InvestmentMonitorStore store;
    private final SearchService search;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration searchTimeout;
    private final int searchResultsPerSymbol;
    private final int maxEvents;
    private final int newsLookbackHours;
    private final ZoneId zone;
    private final int maxFreshQuoteSymbols;
    private final int quoteRotationSlots;
    private final Duration quoteCacheMaxAge;

    public InvestmentMonitoringService(
            InvestmentService investments,
            InvestmentExternalDataService external,
            InvestmentMonitorStore store,
            SearchService search,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${minikun.investment.monitor.search-timeout:20s}") Duration searchTimeout,
            @Value("${minikun.investment.monitor.search-results-per-symbol:3}") int searchResultsPerSymbol,
            @Value("${minikun.investment.monitor.max-events:8}") int maxEvents,
            @Value("${minikun.investment.monitor.news-lookback-hours:48}") int newsLookbackHours,
            @Value("${minikun.investment.monitor.zone:Asia/Bangkok}") String zone,
            @Value("${minikun.investment.monitor.max-fresh-quote-symbols:8}") int maxFreshQuoteSymbols,
            @Value("${minikun.investment.monitor.quote-rotation-slots:3}") int quoteRotationSlots,
            @Value("${minikun.investment.monitor.quote-cache-max-age:72h}") Duration quoteCacheMaxAge) {
        this.investments = Objects.requireNonNull(investments, "investment service must not be null");
        this.external = Objects.requireNonNull(external, "investment external data must not be null");
        this.store = Objects.requireNonNull(store, "investment monitor store must not be null");
        this.search = Objects.requireNonNull(search, "search service must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null")
                .copy().findAndRegisterModules();
        this.clock = Objects.requireNonNull(clock, "investment monitor clock must not be null");
        if (searchTimeout == null || searchTimeout.isZero() || searchTimeout.isNegative()) {
            throw new IllegalArgumentException("investment monitor search timeout must be positive");
        }
        if (searchResultsPerSymbol < 1 || searchResultsPerSymbol > 10) {
            throw new IllegalArgumentException("investment monitor search results per symbol must be 1 to 10");
        }
        if (maxEvents < 1 || maxEvents > 100) {
            throw new IllegalArgumentException("investment monitor max events must be 1 to 100");
        }
        if (newsLookbackHours < 1 || newsLookbackHours > 720) {
            throw new IllegalArgumentException("investment monitor news lookback must be 1 to 720 hours");
        }
        if (maxFreshQuoteSymbols < 1 || maxFreshQuoteSymbols > 8) {
            throw new IllegalArgumentException("investment monitor fresh quote limit must be 1 to 8");
        }
        if (quoteRotationSlots < 0 || quoteRotationSlots >= maxFreshQuoteSymbols) {
            throw new IllegalArgumentException("investment monitor quote rotation slots must be 0 to fresh quote limit - 1");
        }
        if (quoteCacheMaxAge == null || quoteCacheMaxAge.isZero() || quoteCacheMaxAge.isNegative()) {
            throw new IllegalArgumentException("investment monitor quote cache max age must be positive");
        }
        this.searchTimeout = searchTimeout;
        this.searchResultsPerSymbol = searchResultsPerSymbol;
        this.maxEvents = maxEvents;
        this.newsLookbackHours = newsLookbackHours;
        this.maxFreshQuoteSymbols = maxFreshQuoteSymbols;
        this.quoteRotationSlots = quoteRotationSlots;
        this.quoteCacheMaxAge = quoteCacheMaxAge;
        try {
            this.zone = ZoneId.of(Objects.requireNonNullElse(zone, "Asia/Bangkok").trim());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid investment monitor timezone", exception);
        }
    }

    /** Returns the most recent persisted brief unless the caller explicitly asks for fresh data. */
    public Map<String, Object> dailyBrief(String ownerId, boolean refresh) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        if (!refresh) {
            Optional<Map<String, Object>> cached = readLatest(owner);
            if (cached.isPresent()) return cached.get();
        }
        return refresh(owner);
    }

    /** Refreshes prices/news and persists the latest report without sending a notification. */
    public Map<String, Object> refresh(String ownerId) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        Map<String, Object> report = buildReport(owner);
        try {
            store.saveLatestReport(owner, reportDate(report), clock.instant(), objectMapper.writeValueAsString(report));
        } catch (Exception exception) {
            throw new IllegalStateException("investment monitor report could not be persisted", exception);
        }
        return report;
    }

    /** Prepares one daily report; delivery is marked only after notification succeeds. */
    public Optional<Map<String, Object>> prepareDaily(String ownerId) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        LocalDate date = clock.instant().atZone(zone).toLocalDate();
        if (store.deliveredOn(owner, date)) return Optional.empty();
        return Optional.of(refresh(owner));
    }

    public void markDelivered(String ownerId, LocalDate date) {
        store.markDelivered(InvestmentPolicy.requireOwner(ownerId), Objects.requireNonNull(date), clock.instant());
    }

    public boolean deliveredOn(String ownerId, LocalDate date) {
        return store.deliveredOn(InvestmentPolicy.requireOwner(ownerId), Objects.requireNonNull(date));
    }

    public Map<String, Object> plan(String ownerId) {
        String owner = InvestmentPolicy.requireOwner(ownerId);
        PortfolioSummary portfolio = investments.summary(owner);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("owner_id", owner);
        result.put("policy", investments.policy(owner));
        result.put("portfolio", portfolio);
        result.put("active_theses", investments.theses(owner, InvestmentThesisStatus.ACTIVE));
        result.put("quote_priorities", resolvedQuotePriorities(portfolio, investments.quotePriorities(owner)));
        result.put("quote_policy", Map.of(
                "max_fresh_symbols_per_run", maxFreshQuoteSymbols,
                "rotation_slots", quoteRotationSlots,
                "cache_max_age", quoteCacheMaxAge.toString()));
        result.put("execution", Map.of(
                "live_orders", false,
                "paper_simulation", true,
                "human_confirmation_required_for_plan_changes", true));
        return Map.copyOf(result);
    }

    public List<InvestmentNewsEvent> recentNews(String ownerId, int lookbackHours, int limit) {
        if (lookbackHours < 1 || lookbackHours > 720) {
            throw new IllegalArgumentException("news lookback must be between 1 and 720 hours");
        }
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("news limit must be between 1 and 100");
        return store.recentNews(InvestmentPolicy.requireOwner(ownerId),
                clock.instant().minus(Duration.ofHours(lookbackHours)), limit);
    }

    public String formatBrief(Map<String, Object> report) {
        Objects.requireNonNull(report, "investment report must not be null");
        StringBuilder message = new StringBuilder("📈 สรุปการลงทุนประจำวัน ")
                .append(value(report, "report_date", "วันนี้"));
        Object portfolioValue = report.get("portfolio");
        Map<?, ?> portfolio = map(portfolioValue);
        int positionCount = portfolioValue instanceof PortfolioSummary summary
                ? summary.positions().size()
                : portfolio.get("positions") instanceof List<?> list ? list.size() : 0;
        message.append("\n\nพอร์ต: ").append(positionCount).append(" สินทรัพย์");
        Object costBasis = portfolioValue instanceof PortfolioSummary summary
                ? summary.totalOpenCostBasis()
                : portfolio.get("total_open_cost_basis");
        if (costBasis == null) costBasis = portfolio.get("totalOpenCostBasis");
        String baseCurrency = portfolioValue instanceof PortfolioSummary summary
                ? summary.baseCurrency()
                : value(portfolio, "base_currency", value(portfolio, "baseCurrency", ""));
        if (costBasis != null) {
            message.append(" | ต้นทุนคงค้าง ").append(costBasis).append(" ")
                    .append(baseCurrency);
        }
        Map<?, ?> valuation = map(report.get("market_snapshot"));
        String valuationStatus = value(valuation, "status", "");
        if (("ok".equals(valuationStatus) || "partial".equals(valuationStatus))
                && valuation.get("total_market_value") != null) {
            message.append("\nมูลค่าตลาดล่าสุด: ").append(value(valuation, "total_market_value", "ไม่ทราบ"))
                    .append(" ").append(value(valuation, "base_currency", ""));
            message.append(" | P/L ").append(value(valuation, "unrealized_profit_loss", "ไม่ทราบ"));
            Object cachedSymbols = valuation.get("cached_symbols");
            if (cachedSymbols instanceof List<?> list && !list.isEmpty()) {
                message.append(" | ใช้ราคาที่ cache ").append(list.size()).append(" ตัว");
            }
        }

        List<?> events = report.get("news") instanceof Map<?, ?> news
                && news.get("events") instanceof List<?> list ? list : List.of();
        message.append("\n\n📰 ข่าวที่เกี่ยวข้อง");
        if (events.isEmpty()) {
            message.append("\n• ยังไม่พบข่าวใหม่ที่จับคู่กับสินทรัพย์ในแผน");
        } else {
            events.stream().limit(4).forEach(item -> {
                Map<?, ?> event = map(item);
                String title = displayText(value(event, "title", ""), "ข่าวใหม่", 180);
                String summary = displayText(value(event, "summary", ""), "", 220);
                message.append("\n• [").append(value(event, "symbol", "MARKET")).append("] ")
                        .append(title)
                        .append(" — ").append(value(event, "materiality", "LOW"));
                if (!summary.isBlank() && !summary.equalsIgnoreCase(title)) {
                    message.append("\n  สรุป: ").append(summary);
                }
            });
        }

        List<?> recommendations = report.get("recommendations") instanceof List<?> list ? list : List.of();
        message.append("\n\n🧭 สิ่งที่ควรทำ");
        if (recommendations.isEmpty()) {
            message.append("\n• ยังไม่มีรายการที่ต้องทบทวน");
        } else {
            recommendations.stream().limit(4).forEach(item -> {
                Map<?, ?> recommendation = map(item);
                message.append("\n• ").append(value(recommendation, "action", "ทบทวน"))
                        .append(": ").append(value(recommendation, "reason", "ตรวจสอบข้อมูลเพิ่มเติม"));
            });
        }
        if ("partial".equals(value(report, "status", ""))) {
            message.append("\n\n⚠️ รายงานนี้มีข้อมูลบางส่วนที่ดึงไม่ได้ โปรดดู source และเวลาอัปเดตก่อนตัดสินใจ");
        }
        return limitUtf8(message.toString(), MAX_REMINDER_BYTES);
    }

    private Map<String, Object> buildReport(String ownerId) {
        Instant now = clock.instant();
        PortfolioSummary portfolio = investments.summary(ownerId);
        List<InvestmentThesis> theses = investments.theses(ownerId, InvestmentThesisStatus.ACTIVE);
        Map<String, InvestmentQuotePriority> quotePriorities = resolvedQuotePriorities(
                portfolio, investments.quotePriorities(ownerId));
        Set<String> symbols = new LinkedHashSet<>(portfolio.positions().stream()
                .map(PortfolioPosition::symbol).toList());
        theses.stream().map(InvestmentThesis::symbol).forEach(symbols::add);

        CollectionResult collection = collectNews(ownerId, symbols, now);
        Map<String, Object> marketSnapshot = marketSnapshot(ownerId, portfolio, theses, quotePriorities, now);
        List<InvestmentNewsEvent> recent = store.recentNews(ownerId,
                now.minus(Duration.ofHours(newsLookbackHours)), maxEvents);
        List<Map<String, Object>> eventRows = recent.stream()
                .map(event -> eventRow(event, theses, collection.newEventKeys()))
                .toList();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", collection.errors().isEmpty() && !"partial".equals(marketSnapshot.get("status"))
                ? "ok" : "partial");
        report.put("owner_id", ownerId);
        report.put("report_date", now.atZone(zone).toLocalDate());
        report.put("generated_at", now);
        report.put("plan", Map.of(
                "policy", investments.policy(ownerId),
                "active_theses", theses,
                "quote_priorities", quotePriorities,
                "symbols_tracked", List.copyOf(symbols)));
        report.put("portfolio", portfolio);
        report.put("market_snapshot", marketSnapshot);
        report.put("news", Map.of(
                "status", collection.errors().isEmpty() ? "ok" : "partial",
                "events", eventRows,
                "new_event_count", collection.newEventKeys().size(),
                "lookback_hours", newsLookbackHours,
                "as_of", now));
        report.put("recommendations", recommendations(portfolio, recent));
        report.put("limitations", List.of(
                "read-only companion; it never submits live orders",
                "news snippets are evidence for review, not proof of causality",
                "historical prices and backtests do not guarantee future results"));
        if (!collection.errors().isEmpty()) report.put("news_errors", collection.errors());
        return Map.copyOf(report);
    }

    private CollectionResult collectNews(String ownerId, Set<String> symbols, Instant now) {
        List<String> errors = new ArrayList<>();
        Set<String> newKeys = new LinkedHashSet<>();
        for (String symbol : symbols.stream().limit(20).toList()) {
            String query = symbol + " stock market news earnings guidance investor relations";
            try {
                KnowledgeContext context = search.search(new SearchRequest(
                        UUID.randomUUID(), query, searchResultsPerSymbol, now.plus(searchTimeout),
                        new SearchOptions("en", "", "day", true), List.of()));
                if (context == null) continue;
                for (KnowledgeCandidate candidate : context.candidates()) {
                    String url = candidate.provenance().strip();
                    if (url.isBlank() || (!url.startsWith("https://") && !url.startsWith("http://"))) continue;
                    String key = eventKey(symbol, url);
                    if (store.containsNews(ownerId, key)) continue;
                    InvestmentNewsEvent event = new InvestmentNewsEvent(
                            UUID.randomUUID(), ownerId, key, symbol, title(candidate), summary(candidate), url,
                            candidate.source().name(), null, now, materiality(candidate));
                    store.saveNews(event);
                    newKeys.add(key);
                }
            } catch (RuntimeException exception) {
                errors.add(symbol + ": news provider unavailable");
                LOGGER.warn("process=investment_monitor event=news_lookup_failed owner_id={} symbol={} reason={}",
                        ownerId, symbol, exception.getMessage());
            }
        }
        return new CollectionResult(Set.copyOf(newKeys), List.copyOf(errors));
    }

    private Map<String, Object> marketSnapshot(
            String ownerId,
            PortfolioSummary portfolio,
            List<InvestmentThesis> theses,
            Map<String, InvestmentQuotePriority> quotePriorities,
            Instant now) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_currency", portfolio.baseCurrency());
        if (portfolio.positions().isEmpty()) {
            result.put("status", "empty");
            result.put("valuation_basis", "NO_POSITIONS");
            result.put("positions", List.of());
            return Map.copyOf(result);
        }

        List<String> freshRequestSymbols = freshQuoteSymbols(
                portfolio, theses, quotePriorities, now.atZone(zone).toLocalDate());
        Map<String, CachedQuote> cached = cachedQuotes(ownerId);
        Map<String, InvestmentExternalDataService.MarketQuote> quotes = Map.of();
        String quoteError = null;
        if (!external.marketDataConfigured()) {
            quoteError = "Twelve Data API key is not configured";
        } else {
            // ponytail: one monitor caller is assumed; add a shared per-key limiter if more jobs are introduced.
            try {
                quotes = external.latestQuotes(freshRequestSymbols);
            } catch (RuntimeException exception) {
                quoteError = "Twelve Data quote provider unavailable";
                LOGGER.warn("process=investment_monitor event=quote_lookup_failed owner_id={} reason={}",
                        ownerId, exception.getMessage());
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal totalMarket = BigDecimal.ZERO;
        List<String> missing = new ArrayList<>();
        List<String> freshSymbols = new ArrayList<>();
        List<String> cachedSymbols = new ArrayList<>();
        List<String> staleSymbols = new ArrayList<>();
        for (PortfolioPosition position : portfolio.positions()) {
            InvestmentExternalDataService.MarketQuote quote = freshRequestSymbols.contains(position.symbol())
                    ? quotes.get(position.symbol()) : null;
            CachedQuote cachedQuote = cached.get(position.symbol());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", position.symbol());
            row.put("quantity", position.quantity());
            row.put("cost_basis", position.costBasis());
            row.put("quote_priority", quotePriorities.getOrDefault(
                    position.symbol(), InvestmentQuotePriority.NORMAL).name());

            BigDecimal price = null;
            String currency = null;
            Instant observedAt = null;
            String source = null;
            String quoteStatus;
            if (quote != null && quote.price() != null) {
                price = quote.price();
                currency = quote.currency();
                observedAt = quote.observedAt();
                source = quote.source();
                quoteStatus = "fresh";
                freshSymbols.add(position.symbol());
            } else if (cachedQuote != null) {
                price = cachedQuote.price();
                currency = cachedQuote.currency();
                observedAt = cachedQuote.observedAt();
                source = cachedQuote.source();
                quoteStatus = "cached";
                cachedSymbols.add(position.symbol());
                if (observedAt == null || observedAt.plus(quoteCacheMaxAge).isBefore(now)) {
                    staleSymbols.add(position.symbol());
                }
            } else {
                quoteStatus = "missing";
                missing.add(position.symbol());
            }

            row.put("quote_status", quoteStatus);
            if (price != null) row.put("market_price", price);
            if (currency != null && !currency.isBlank()) row.put("quote_currency", currency);
            if (observedAt != null) {
                row.put("quote_observed_at", observedAt);
                row.put("quote_age_seconds", Math.max(0, Duration.between(observedAt, now).getSeconds()));
            }
            if (source != null && !source.isBlank()) row.put("quote_source", source);

            if (price == null) {
                row.put("status", "quote_missing");
            } else if (currency == null || !portfolio.baseCurrency().equalsIgnoreCase(currency)) {
                missing.add(position.symbol());
                row.put("status", "currency_mismatch");
            } else {
                BigDecimal value = price.multiply(position.quantity(), MATH);
                totalMarket = totalMarket.add(value, MATH);
                row.put("status", "fresh".equals(quoteStatus) ? "ok" : quoteStatus);
                row.put("market_value", value);
                row.put("unrealized_profit_loss", value.subtract(position.costBasis(), MATH));
            }
            rows.add(Map.copyOf(row));
        }
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            Object marketValue = row.get("market_value");
            if (marketValue instanceof BigDecimal value) {
                Map<String, Object> mutable = new LinkedHashMap<>(row);
                mutable.put("market_allocation_percent", percent(value, totalMarket));
                rows.set(index, Map.copyOf(mutable));
            }
        }
        result.put("status", quoteError == null && missing.isEmpty() && cachedSymbols.isEmpty() ? "ok" : "partial");
        result.put("valuation_basis", cachedSymbols.isEmpty() ? "LATEST_QUOTE" : "LATEST_OR_CACHED_QUOTE");
        result.put("total_market_value", totalMarket);
        result.put("total_cost_basis", portfolio.totalOpenCostBasis());
        result.put("unrealized_profit_loss", totalMarket.subtract(portfolio.totalOpenCostBasis(), MATH));
        result.put("positions", List.copyOf(rows));
        result.put("fresh_request_symbols", List.copyOf(freshRequestSymbols));
        result.put("fresh_symbols", List.copyOf(freshSymbols));
        result.put("cached_symbols", List.copyOf(cachedSymbols));
        result.put("stale_symbols", List.copyOf(staleSymbols));
        result.put("missing_symbols", List.copyOf(new LinkedHashSet<>(missing)));
        result.put("as_of", now);
        if (quoteError != null) {
            result.put("quote_error", quoteError);
            if (!external.marketDataConfigured()) {
                result.put("setup_required", List.of("MINIKUN_INVESTMENT_TWELVE_DATA_API_KEY"));
            }
        }
        return Map.copyOf(result);
    }

    private List<String> freshQuoteSymbols(
            PortfolioSummary portfolio,
            List<InvestmentThesis> theses,
            Map<String, InvestmentQuotePriority> quotePriorities,
            LocalDate date) {
        List<String> symbols = portfolio.positions().stream().map(PortfolioPosition::symbol).distinct().toList();
        if (symbols.size() <= maxFreshQuoteSymbols) return symbols;

        Set<String> thesisSymbols = theses.stream().map(InvestmentThesis::symbol).collect(java.util.stream.Collectors.toSet());
        Map<String, BigDecimal> costBySymbol = new LinkedHashMap<>();
        portfolio.positions().forEach(position -> costBySymbol.put(position.symbol(), position.costBasis()));
        List<String> ranked = new ArrayList<>(symbols);
        ranked.sort(Comparator
                .comparing((String symbol) -> quotePriorities.getOrDefault(
                        symbol, InvestmentQuotePriority.NORMAL) == InvestmentQuotePriority.MINOR)
                .thenComparing(symbol -> quotePriorities.getOrDefault(
                        symbol, InvestmentQuotePriority.NORMAL) == InvestmentQuotePriority.HIGH ? 0 : 1)
                .thenComparing(symbol -> !thesisSymbols.contains(symbol))
                .thenComparing(symbol -> costBySymbol.getOrDefault(symbol, BigDecimal.ZERO), Comparator.reverseOrder())
                .thenComparing(String::compareTo));

        int fixedLimit = maxFreshQuoteSymbols - quoteRotationSlots;
        List<String> fixed = ranked.stream()
                .filter(symbol -> quotePriorities.getOrDefault(symbol, InvestmentQuotePriority.NORMAL)
                        != InvestmentQuotePriority.MINOR)
                .limit(fixedLimit)
                .toList();
        Set<String> fixedSymbols = Set.copyOf(fixed);
        List<String> rotating = ranked.stream().filter(symbol -> !fixedSymbols.contains(symbol)).toList();
        int rotatingCount = Math.min(quoteRotationSlots, rotating.size());
        List<String> selected = new ArrayList<>(fixed);
        if (!rotating.isEmpty()) {
            int start = Math.floorMod(date.toEpochDay() * (long) Math.max(1, rotatingCount), rotating.size());
            for (int offset = 0; offset < rotatingCount; offset++) {
                selected.add(rotating.get((start + offset) % rotating.size()));
            }
        }
        return List.copyOf(selected);
    }

    private Map<String, CachedQuote> cachedQuotes(String ownerId) {
        Map<String, Object> latest = readLatest(ownerId).orElse(Map.of());
        Map<?, ?> snapshot = map(latest.get("market_snapshot"));
        Object positions = snapshot.get("positions");
        if (!(positions instanceof List<?> rows)) return Map.of();
        Map<String, CachedQuote> result = new LinkedHashMap<>();
        for (Object item : rows) {
            Map<?, ?> row = map(item);
            String symbol = value(row, "symbol", "").toUpperCase(Locale.ROOT);
            BigDecimal price = decimalValue(row.get("market_price"));
            Instant observedAt = instantValue(row.get("quote_observed_at"));
            String currency = value(row, "quote_currency", "");
            if (!symbol.isBlank() && price != null && !currency.isBlank() && observedAt != null) {
                result.put(symbol, new CachedQuote(price, currency, observedAt,
                        value(row, "quote_source", "cached")));
            }
        }
        return Map.copyOf(result);
    }

    private Map<String, InvestmentQuotePriority> resolvedQuotePriorities(
            PortfolioSummary portfolio, Map<String, InvestmentQuotePriority> stored) {
        Map<String, InvestmentQuotePriority> result = new LinkedHashMap<>();
        portfolio.positions().forEach(position -> result.put(position.symbol(), stored.getOrDefault(
                position.symbol(), InvestmentQuotePriority.NORMAL)));
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> recommendations(PortfolioSummary portfolio, List<InvestmentNewsEvent> events) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (portfolio.positions().isEmpty()) {
            result.add(Map.of("action", "setup_plan", "reason", "บันทึกพอร์ตและเป้าหมายก่อนเริ่มติดตาม"));
            return List.copyOf(result);
        }
        if (!portfolio.warnings().isEmpty()) {
            result.add(Map.of("action", "review_allocation", "reason", "พบสัดส่วนสินทรัพย์เกินกติกาที่ตั้งไว้"));
        }
        long highEvents = events.stream().filter(event -> "HIGH".equals(event.materiality())).count();
        if (highEvents > 0) {
            result.add(Map.of("action", "review_thesis", "reason", highEvents + " ข่าวระดับสูงอาจกระทบเหตุผลเดิม"));
        } else if (!events.isEmpty()) {
            result.add(Map.of("action", "watch", "reason", "มีข่าวใหม่ ควรติดตามหลักฐานเพิ่มเติมก่อนเปลี่ยนแผน"));
        } else {
            result.add(Map.of("action", "no_action", "reason", "ยังไม่พบข้อมูลใหม่ที่เปลี่ยนแผนอย่างมีนัยสำคัญ"));
        }
        return List.copyOf(result);
    }

    private Map<String, Object> eventRow(
            InvestmentNewsEvent event, List<InvestmentThesis> theses, Set<String> newEventKeys) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", event.symbol());
        result.put("title", event.title());
        result.put("summary", event.summary());
        result.put("url", event.url());
        result.put("source", event.source());
        result.put("materiality", event.materiality());
        if (event.publishedAt() != null) result.put("published_at", event.publishedAt());
        result.put("discovered_at", event.discoveredAt());
        result.put("new", newEventKeys.contains(event.eventKey()));
        theses.stream().filter(thesis -> thesis.symbol().equals(event.symbol())).findFirst().ifPresent(thesis -> {
            result.put("thesis", thesis.summary());
            result.put("invalidation", thesis.invalidation());
            result.put("thesis_review", "compare this news with the stored thesis and invalidation condition");
        });
        return Map.copyOf(result);
    }

    private String title(KnowledgeCandidate candidate) {
        String content = candidate.content().strip();
        String provenance = candidate.provenance().strip();
        int urlStart = provenance.isBlank() ? -1 : content.indexOf(provenance);
        if (urlStart > 0) {
            String title = content.substring(0, urlStart).replaceFirst("\\s*\\($", "").strip();
            if (!title.isBlank()) return truncate(title, 500);
        }
        int marker = content.indexOf(" (");
        return truncate(marker > 0 ? content.substring(0, marker) : content, 500);
    }

    private String summary(KnowledgeCandidate candidate) {
        String content = candidate.content().strip();
        String provenance = candidate.provenance().strip();
        int urlStart = provenance.isBlank() ? -1 : content.indexOf(provenance);
        if (urlStart >= 0) {
            String summary = content.substring(urlStart + provenance.length())
                    .replaceFirst("^\\s*\\)\\s*:\\s*", "").strip();
            if (!summary.isBlank()) return truncate(summary, 4_000);
        }
        int marker = content.indexOf("): ");
        return truncate(marker >= 0 ? content.substring(marker + 3) : content, 4_000);
    }

    private String materiality(KnowledgeCandidate candidate) {
        String content = candidate.content();
        if (HIGH_MATERIALITY.matcher(content).find()) return "HIGH";
        if (MEDIUM_MATERIALITY.matcher(content).find()) return "MEDIUM";
        return "LOW";
    }

    private String eventKey(String symbol, String url) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((symbol + "|" + url).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Optional<Map<String, Object>> readLatest(String ownerId) {
        Optional<String> json = store.latestReportJson(ownerId);
        if (json.isEmpty() || json.get().isBlank()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json.get(), MAP));
        } catch (Exception exception) {
            LOGGER.warn("process=investment_monitor event=latest_report_invalid owner_id={} reason={}",
                    ownerId, exception.getMessage());
            return Optional.empty();
        }
    }

    private LocalDate reportDate(Map<String, Object> report) {
        Object value = report.get("report_date");
        return value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) return null;
        try {
            return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Instant instantValue(Object value) {
        if (value instanceof Instant instant) return instant;
        if (value == null || value.toString().isBlank()) return null;
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException ignored) {
            try {
                BigDecimal epochSeconds = new BigDecimal(value.toString());
                BigDecimal fractionalSeconds = epochSeconds.remainder(BigDecimal.ONE);
                return Instant.ofEpochSecond(epochSeconds.longValue(),
                        fractionalSeconds.movePointRight(9).longValue());
            } catch (RuntimeException exception) {
                return null;
            }
        }
    }

    private BigDecimal percent(BigDecimal value, BigDecimal total) {
        return total.signum() == 0
                ? BigDecimal.ZERO
                : value.multiply(HUNDRED, MATH).divide(total, 6, RoundingMode.HALF_UP);
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return Objects.requireNonNullElse(value, "");
        return value.substring(0, Math.max(0, limit - 1)).stripTrailing() + "…";
    }

    private String limitUtf8(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        String suffix = "\n\n… (ย่อเพื่อส่งเป็นข้อความแจ้งเตือน)";
        int suffixBytes = suffix.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder result = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + codePointBytes + suffixBytes > maxBytes) break;
            result.appendCodePoint(codePoint);
            bytes += codePointBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString().stripTrailing() + suffix;
    }

    private String displayText(String value, String fallback, int limit) {
        String normalized = Objects.requireNonNullElse(value, "")
                .replaceAll("(?i)https?://\\S+", "")
                .replaceAll("\\s+", " ").strip();
        return normalized.isBlank() ? fallback : truncate(normalized, limit);
    }

    private Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private String value(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value.toString();
    }

    private String value(Object value, String key, String fallback) {
        return value instanceof Map<?, ?> map ? value(map, key, fallback) : fallback;
    }

    private record CachedQuote(BigDecimal price, String currency, Instant observedAt, String source) { }

    private record CollectionResult(Set<String> newEventKeys, List<String> errors) { }
}
