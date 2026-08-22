package com.minikun.search.internal;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** Avoids a task-model round trip for unambiguous search and non-search requests. */
public final class FastPathSearchDecisionService implements SearchDecisionService {
    private static final String FAST_PATH_COUNTER = "minikun.search.fast_path.decisions";
    private static final Pattern LIVE_INFORMATION = Pattern.compile(
            "(ค้นหา|เสิร์ช|ข่าว|อากาศ|พยากรณ์|ราคา|หุ้น|คริปโต|คะแนน|ผลการแข่งขัน|ตารางแข่ง|"
                    + "เที่ยวบิน|จราจร|ร้าน|ใกล้ฉัน|ที่ไหน|ใครเป็น|ล่าสุด|ปัจจุบัน|สุขภาพ|ยา|การแพทย์|"
                    + "กฎหมาย|ภาษี|ลงทุน|การเงิน|search|look\\s*up|news|weather|forecast|price|stock|crypto|"
                    + "score|schedule|flight|traffic|near me|current|latest|health|medical|legal|tax|invest|finance|"
                    + "https?://|www\\.)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CASUAL_CONVERSATION = Pattern.compile(
            "(สวัสดี|หวัดดี|เป็น(?:ยัง)?ไง|ขอบคุณ|ขอบใจ|ฝันดี|คิดถึง|เหงา|เหนื่อย|เครียด|เศร้า|ดีใจ|"
                    + "ไม่สบายใจ|คุย(?:กัน|เล่น|เป็นเพื่อน|แบบ)|คู่หู|เพื่อนคุย|companion|hello|hi\\b|"
                    + "thanks|thank you|how are you|i feel|i['’]?m (?:tired|sad|lonely|stressed))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern GENERAL_KNOWLEDGE = Pattern.compile(
            "^(?:(?:อธิบาย|ช่วยอธิบาย|คืออะไร|ทำไม|อย่างไร|แปล|สรุป).*|"
                    + "(?:explain|what is|how does|why does|translate|summarize)\\b.*)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final SearchDecisionService delegate;
    private final SearchDecisionService rules;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public FastPathSearchDecisionService(SearchDecisionService delegate, SearchDecisionService rules) {
        this(delegate, rules, null, true);
    }

    public FastPathSearchDecisionService(
            SearchDecisionService delegate, SearchDecisionService rules, MeterRegistry meterRegistry) {
        this(delegate, rules, meterRegistry, true);
    }

    public FastPathSearchDecisionService(
            SearchDecisionService delegate, SearchDecisionService rules,
            MeterRegistry meterRegistry, boolean enabled) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.rules = Objects.requireNonNull(rules, "rules must not be null");
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    @Override
    public SearchDecision decide(String query) {
        SearchDecision fast = fastDecision(query);
        return fast == null ? delegate.decide(query) : fast;
    }

    @Override
    public SearchDecision decide(String query, String conversationContext) {
        SearchDecision fast = fastDecision(query);
        return fast == null ? delegate.decide(query, conversationContext) : fast;
    }

    private SearchDecision fastDecision(String query) {
        if (!enabled) {
            return null;
        }
        if (query == null || query.isBlank()) {
            return fastNoSearch("", "blank");
        }
        String value = query.trim().toLowerCase(Locale.ROOT);
        if (!LIVE_INFORMATION.matcher(value).find()
                && CASUAL_CONVERSATION.matcher(value).find()) {
            return fastNoSearch(query, "conversation");
        }
        if (!LIVE_INFORMATION.matcher(value).find()
                && GENERAL_KNOWLEDGE.matcher(value).matches()) {
            return fastNoSearch(query, "general_knowledge");
        }
        SearchDecision ruleDecision = rules.decide(query);
        if (ruleDecision.shouldSearch()) {
            return ruleDecision;
        }
        return null;
    }

    private SearchDecision fastNoSearch(String query, String reason) {
        try {
            Counter.builder(FAST_PATH_COUNTER)
                    .tag("reason", reason)
                    .register(meterRegistry)
                    .increment();
        } catch (RuntimeException ignored) {
            // Search routing must remain available if metrics are unavailable.
        }
        return new SearchDecision(false, query, SearchDecisionReason.GENERAL_KNOWLEDGE);
    }
}
