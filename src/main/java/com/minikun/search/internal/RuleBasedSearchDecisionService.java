package com.minikun.search.internal;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.util.List;
import java.util.Locale;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuleBasedSearchDecisionService implements SearchDecisionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuleBasedSearchDecisionService.class);
    private static final String DECISION_TIMER = "minikun.search.decision.duration";
    private static final String NO_SEARCH_COUNTER = "minikun.search.quality.no_search";
    private static final List<String> KEYWORDS = List.of(
            "search", "ค้นหา", "แนะนำ", "ร้าน", "เมนู", "อาหาร", "ราคา", "ที่ไหน", "อยู่ที่ไหน",
            "ข่าว", "ล่าสุด", "วันนี้", "ปัจจุบัน", "ข้อมูล", "current", "latest", "news", "recommend",
            "where", "who is", "what is", "ค้นคว้า", "วิจัย", "เจาะลึก", "สืบค้น",
            "ตรวจสอบข้อเท็จจริง", "research", "investigate", "fact-check", "deep dive");
    private final MeterRegistry meterRegistry;

    public RuleBasedSearchDecisionService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public SearchDecision decide(String query) {
        Timer.Sample sample = startTimer();
        SearchDecision decision = null;
        try {
            if (query == null || query.isBlank()) {
                decision = new SearchDecision(false, "", SearchDecisionReason.GENERAL_KNOWLEDGE);
                return decision;
            }
            String normalized = query.trim().toLowerCase(Locale.ROOT);
            boolean shouldSearch = KEYWORDS.stream().anyMatch(normalized::contains);
            decision = new SearchDecision(
                    shouldSearch,
                    query.trim(),
                    shouldSearch ? SearchDecisionReason.CURRENT_INFORMATION
                            : SearchDecisionReason.GENERAL_KNOWLEDGE);
            return decision;
        } finally {
            recordDecision(sample);
            recordQuality(decision);
            logDecision(decision);
        }
    }

    private Timer.Sample startTimer() {
        try {
            return Timer.start(meterRegistry);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void recordDecision(Timer.Sample sample) {
        if (sample == null) {
            return;
        }
        try {
            sample.stop(meterRegistry.timer(DECISION_TIMER, "mode", "RULE"));
        } catch (RuntimeException ignored) {
            // Observability must not affect decision execution.
        }
    }

    private void logDecision(SearchDecision decision) {
        if (decision == null) {
            return;
        }
        try {
            LOGGER.debug("Search decision mode=RULE shouldSearch={} reason={}",
                    decision.shouldSearch(), decision.reason());
        } catch (RuntimeException ignored) {
            // Logging must not affect decision execution.
        }
    }

    private void recordQuality(SearchDecision decision) {
        if (decision == null || decision.shouldSearch() || meterRegistry == null) {
            return;
        }
        try {
            Counter.builder(NO_SEARCH_COUNTER).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect decision execution.
        }
    }
}
