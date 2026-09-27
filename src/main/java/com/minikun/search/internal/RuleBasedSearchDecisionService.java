package com.minikun.search.internal;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.GroundingIntent;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuleBasedSearchDecisionService implements SearchDecisionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RuleBasedSearchDecisionService.class);
    private static final String DECISION_TIMER = "minikun.search.decision.duration";
    private static final String NO_SEARCH_COUNTER = "minikun.search.quality.no_search";
    private static final Pattern LOCAL_DISCOVERY = Pattern.compile(
            "(?s)(?=.*(?:ร้าน|คาเฟ่|ที่พัก|โรงแรม|สถานที่(?:ท่องเที่ยว)?|ที่เที่ยว|เที่ยว|ไปไหนดี|พิพิธภัณฑ์|สวน|ตลาด|"
                    + "แกลเลอรี|แกลลอรี่|จุดชมวิว|วัด|ชายหาด|ทะเล|อุทยาน|กิจกรรม|ร้านหนังสือ|ห้องสมุด|"
                    + "สวนสัตว์|พิพิธภัณฑ์สัตว์น้ำ|ห้าง|โรงหนัง|ร้านค้า|restaurant|cafe|hotel|shop|venue|"
                    + "museum|park|market|gallery|viewpoint|attraction|landmark|landmarks|temple|beach|trail|activity|"
                    + "bookstore|library|zoo|aquarium|mall|cinema|theater|things to do|place|places))"
                    + "(?=.*(?:แนะนำ|ช่วยหา|หาร้าน|อยากไป|สนใจ|ไปไหนดี|ไปเที่ยว|เที่ยว|ใกล้|แถว|ย่าน|"
                    + "เปิด|ปิด|เวลา|รีวิว|งบ|เดินทาง|recommend|suggest|want to visit|interested|"
                    + "ที่ไหนดี|ไหนดี|where should|where should i go|where should we go|near|open|hours|review|visit|"
                    + "budget|how to get|things to do|travel|trip)).*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final List<String> KEYWORDS = List.of(
            "search", "ค้นหา", "ค้นข้อมูล", "หาข้อมูล", "เช็กข้อมูล", "เช็คข้อมูล", "แนะนำ", "ร้าน", "เมนู",
            "อาหาร", "ราคา", "ที่ไหน", "อยู่ที่ไหน", "ข่าว", "ล่าสุด", "วันนี้", "ตอนนี้", "ปัจจุบัน",
            "ข้อมูล", "current", "latest", "news", "recommend",
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
            boolean localDiscovery = isLocalDiscovery(normalized);
            boolean shouldSearch = localDiscovery || GroundingIntent.requiresSource(query, "")
                    || KEYWORDS.stream().anyMatch(normalized::contains);
            decision = new SearchDecision(
                    shouldSearch,
                    query.trim(),
                    localDiscovery ? SearchDecisionReason.EXTERNAL_RESOURCE
                            : shouldSearch ? SearchDecisionReason.CURRENT_INFORMATION
                            : SearchDecisionReason.GENERAL_KNOWLEDGE);
            return decision;
        } finally {
            recordDecision(sample);
            recordQuality(decision);
            logDecision(decision);
        }
    }

    static boolean isLocalDiscovery(String query) {
        return query != null && LOCAL_DISCOVERY.matcher(query).find();
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
