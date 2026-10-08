package com.minikun.search.internal;

import com.minikun.conversation.continuity.ConversationContinuity;
import com.minikun.conversation.continuity.ConversationContinuityResolver;
import com.minikun.model.CooperationRouter;
import com.minikun.search.SearchDecisionService;
import com.minikun.search.GroundingIntent;
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
            "(ค้นหา|ค้นข้อมูล|หาข้อมูล|เช็กข้อมูล|เช็คข้อมูล|เช็กเรื่อง|เช็คเรื่อง|เสิร์ช|ข่าว|อากาศ|พยากรณ์|"
                    + "ราคา|หุ้น|คริปโต|คะแนน|ผลการแข่งขัน|ตารางแข่ง|"
                    + "เที่ยวบิน|จราจร|ร้าน|ใกล้ฉัน|ที่ไหน|ใครเป็น|ล่าสุด|ปัจจุบัน|สุขภาพ|ยา|การแพทย์|"
                    + "ตอนนี้|"
                    + "ค้นคว้า|วิจัย|เจาะลึก|สืบค้น|ตรวจสอบข้อเท็จจริง|"
                    + "กฎหมาย|ภาษี|ลงทุน|การเงิน|search|look\\s*up|news|weather|forecast|price|stock|crypto|"
                    + "score|schedule|flight|traffic|near me|current|latest|health|medical|legal|tax|invest|finance|"
                    + "research|investigat(?:e|ion)|fact[- ]?check|deep\\s+dive|"
                    + "https?://|www\\.)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern MODEL_LOOKUP = Pattern.compile(
            "(?iu)(recommend|suggest|restaurant|cafe|museum|library|best|quote|citation|official)");
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
    private final ConversationContinuityResolver continuityResolver;
    private final CooperationRouter cooperationRouter;
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
        // No keyword search overrides in LLM mode; only the known no-search paths below.
        this.rules = rules;
        this.continuityResolver = new ConversationContinuityResolver();
        this.cooperationRouter = new CooperationRouter();
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    @Override
    public SearchDecision decide(String query) {
        if (rules != null && GroundingIntent.requiresSource(query, "")) {
            return new SearchDecision(true, query, SearchDecisionReason.FACT_LOOKUP);
        }
        if (dynamicLocalDiscovery(query)) {
            return requireLocalSearch(query, delegate.decide(query));
        }
        SearchDecision fast = fastDecision(query);
        return fast == null ? delegate.decide(query) : fast;
    }

    @Override
    public SearchDecision decide(String query, String conversationContext) {
        if (rules == null && conversationContext != null && !conversationContext.isBlank()) {
            return delegate.decide(query, conversationContext);
        }
        if (rules != null && GroundingIntent.requiresSource(query, conversationContext)) {
            return new SearchDecision(true, query, SearchDecisionReason.FACT_LOOKUP);
        }
        if (dynamicLocalDiscovery(query)) {
            return requireLocalSearch(query, delegate.decide(query, conversationContext));
        }
        SearchDecision fast = fastDecision(query);
        if (fast != null) {
            return fast;
        }
        ConversationContinuity continuity = continuityResolver.resolve(query, conversationContext);
        if (!continuity.followUp()) {
            return delegate.decide(query, conversationContext);
        }
        SearchDecision contextual = fastDecision(
                continuity.previousTopic() + " " + query);
        return contextual == null
                ? delegate.decide(continuity.resolvedQuery(), conversationContext)
                : new SearchDecision(
                        contextual.shouldSearch(), continuity.resolvedQuery(), contextual.reason());
    }

    private SearchDecision fastDecision(String query) {
        if (!enabled) {
            return null;
        }
        if (query == null || query.isBlank()) {
            return fastNoSearch("", "blank");
        }
        String value = query.trim().toLowerCase(Locale.ROOT);
        boolean live = LIVE_INFORMATION.matcher(value).find()
                || rules == null && MODEL_LOOKUP.matcher(value).find();
        if (!live
                && (rules == null ? CASUAL_CONVERSATION.matcher(value).matches()
                                  : CASUAL_CONVERSATION.matcher(value).find())) {
            return fastNoSearch(query, "conversation");
        }
        if (!live
                && GENERAL_KNOWLEDGE.matcher(value).matches()) {
            return fastNoSearch(query, "general_knowledge");
        }
        if (!live
                && "creative_request".equals(cooperationRouter.decide(value).reason())) {
            return fastNoSearch(query, "creative_content");
        }
        if (rules == null) return null;
        SearchDecision ruleDecision = rules.decide(query);
        if (ruleDecision.shouldSearch()) {
            return ruleDecision;
        }
        return null;
    }

    private boolean dynamicLocalDiscovery(String query) {
        return enabled && rules != null && RuleBasedSearchDecisionService.isLocalDiscovery(query);
    }

    private SearchDecision requireLocalSearch(String query, SearchDecision decision) {
        if (decision != null && decision.shouldSearch()) return decision;
        return new SearchDecision(true, query, SearchDecisionReason.EXTERNAL_RESOURCE,
                decision == null ? null : decision.planHints());
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
