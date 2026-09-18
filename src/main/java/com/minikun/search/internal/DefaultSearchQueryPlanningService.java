package com.minikun.search.internal;

import com.minikun.conversation.continuity.ConversationContinuity;
import com.minikun.conversation.continuity.ConversationContinuityResolver;
import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchQueryPlan;
import com.minikun.search.model.SearchPlanHints;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Deterministic, conservative planner. It never invents terms. */
public final class DefaultSearchQueryPlanningService implements SearchQueryPlanningService {
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern THAI_IMAGE_PREFIX = Pattern.compile(
            "^(?:(?:หา|ขอ|แสดง|ดู|อยาก(?:ดู|ได้|เห็น)|ต้องการ)\\s*)?(?:รูป|ภาพ)(?:ของ)?\\s*|"
                    + "^มี\\s*(?:รูป|ภาพ)(?:ของ)?\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENGLISH_IMAGE_PREFIX = Pattern.compile(
            "^(?:(?:find|show|get|display|search for|look for|give me|want to see)\\s+)?"
                    + "(?:me\\s+)?(?:some\\s+|a\\s+)?(?:images?|pictures?|photos?)\\s+(?:of\\s+)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENGLISH_VISUAL_LOOK_QUERY = Pattern.compile(
            "^what\\s+does\\s+(.+?)\\s+look\\s+like[?!.]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern THAI_NAMED_ENTITY_WRAPPER = Pattern.compile(
            "^(?:ข้อมูล|ประวัติ)(?:ของ)?(?:นักวาด|ศิลปิน|นักเขียน|นักร้อง|นักแสดง|บุคคล|คน)?"
                    + "(?:ที่)?ชื่อ\\s+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final List<String> THAI_PREFIXES = List.of(
            "ช่วยค้นคว้าเรื่อง", "ช่วยค้นคว้า", "ค้นคว้าเรื่อง", "วิจัยเรื่อง", "เจาะลึกเรื่อง",
            "ช่วยค้นหา", "ช่วยแนะนำ", "ช่วยหา", "ค้นหาให้หน่อย", "อยากรู้ว่า", "ช่วยบอกหน่อยว่า", "ขอข้อมูล");
    private static final List<String> ENGLISH_PREFIXES = List.of(
            "please do deep research on", "deep research on", "research", "investigate",
            "please search for", "search for", "find out", "look up", "can you tell me", "i want to know");
    private static final List<String> THAI_STOPWORDS = List.of(
            "หน่อยสิ", "หน่อย", "ให้หน่อย", "ครับ", "ค่ะ", "นะ", "ที");
    private static final List<String> ENGLISH_STOPWORDS = List.of(
            "please", "could", "you", "tell", "me", "about", "what", "is", "the", "a", "an", "for");
    private final MeterRegistry meterRegistry;
    private final int maxAlternates;
    private final ConversationContinuityResolver continuityResolver = new ConversationContinuityResolver();

    public DefaultSearchQueryPlanningService() {
        this(null, 2);
    }

    public DefaultSearchQueryPlanningService(MeterRegistry meterRegistry) {
        this(meterRegistry, 2);
    }

    public DefaultSearchQueryPlanningService(MeterRegistry meterRegistry, int maxAlternates) {
        if (maxAlternates < 0 || maxAlternates > SearchQueryPlan.MAX_ALTERNATES) {
            throw new IllegalArgumentException("max alternates must be between 0 and "
                    + SearchQueryPlan.MAX_ALTERNATES);
        }
        this.meterRegistry = meterRegistry;
        this.maxAlternates = maxAlternates;
    }

    @Override
    public SearchQueryPlan plan(String query, SearchDecision decision) {
        return plan(query, decision, "");
    }

    @Override
    public SearchQueryPlan plan(String query, SearchDecision decision, String conversationContext) {
        increment("minikun.search.query.plan.count");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        String original = normalize(query);
        if (!decision.shouldSearch() || original.isBlank()) {
            return new SearchQueryPlan(false, original.isBlank() ? " " : original, "", List.of(), List.of(),
                    detectLanguage(original), "general", "", 1.0, "search_not_requested");
        }
        SearchPlanHints hints = decision.planHints();
        boolean researchIntent = isResearchIntent(original);
        boolean semanticPlan = hints.available() && !hints.primaryQuery().isBlank();
        String primary = semanticPlan ? normalize(hints.primaryQuery()) : stripConversationalPrefix(original);
        if (!semanticPlan && decision.reason() == SearchDecisionReason.IMAGE_REQUEST) {
            primary = focusImageQuery(primary);
        }
        ConversationContinuity continuity = continuityResolver.resolve(original, conversationContext);
        boolean contextual = continuity.followUp();
        if (contextual && !semanticPlan) {
            if (decision.reason() == SearchDecisionReason.IMAGE_REQUEST
                    && continuity.visualFollowUp()
                    && !continuity.searchAnchor().isBlank()
                    && original.toLowerCase(Locale.ROOT).contains("ผลงาน")) {
                primary = continuity.searchAnchor() + " artwork";
            } else {
                String previousTopic = trimNoise(stripConversationalPrefix(continuity.previousTopic()));
                if (!previousTopic.isBlank() && !containsTopic(primary, previousTopic)) {
                    primary = previousTopic + " " + primary;
                }
            }
        }
        primary = trimNoise(primary);
        if (primary.isBlank()) {
            primary = original;
        }
        String language = detectLanguage(primary);
        List<String> terms = coreTerms(primary);
        if (!semanticPlan && "th".equals(language) && terms.size() > 1) {
            primary = String.join(" ", terms);
        }
        String timeRange = detectTimeRange(original + " " + primary);
        String intent = semanticIntent(hints.intent(), primary, timeRange, decision, researchIntent);
        String reason = semanticPlan ? "semantic_plan" : contextual ? "contextual_query" : "deterministic_core_query";
        String plannedPrimary = primary;
        List<String> alternates = java.util.stream.Stream.concat(
                        hints.alternateQueries().stream(),
                        alternateQueries(primary, terms, language, intent).stream())
                .filter(value -> !value.equalsIgnoreCase(plannedPrimary))
                .distinct()
                .limit(maxAlternates)
                .toList();
        List<String> evidenceNeeds = hints.evidenceNeeds().isEmpty() && "recommendation".equals(intent)
                ? defaultRecommendationEvidence(original)
                : hints.evidenceNeeds();
        double confidence = hints.confidence() > 0.0 ? hints.confidence() : contextual ? 0.88 : 0.92;
        return new SearchQueryPlan(true, original, primary, alternates, terms,
                language, intent, timeRange, confidence, reason, evidenceNeeds, hints.location());
    }

    private void increment(String name) {
        if (meterRegistry == null) {
            return;
        }
        try {
            Counter.builder(name).register(meterRegistry).increment();
        } catch (RuntimeException ignored) {
            // Observability must not affect query planning.
        }
    }

    private String stripConversationalPrefix(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String prefix : THAI_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return stripNamedEntityWrapper(value.substring(prefix.length()).trim());
            }
        }
        for (String prefix : ENGLISH_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return value.substring(prefix.length()).trim();
            }
        }
        return stripNamedEntityWrapper(value);
    }

    private String stripNamedEntityWrapper(String value) {
        return THAI_NAMED_ENTITY_WRAPPER.matcher(value).replaceFirst("").trim();
    }

    static String focusImageQuery(String value) {
        String result = java.util.Objects.requireNonNullElse(value, "");
        result = THAI_IMAGE_PREFIX.matcher(result).replaceFirst("");
        result = ENGLISH_IMAGE_PREFIX.matcher(result).replaceFirst("");
        result = ENGLISH_VISUAL_LOOK_QUERY.matcher(result).replaceFirst("$1");
        return result.trim();
    }

    private String trimNoise(String value) {
        String result = value.replaceAll("^[\\s:,-]+|[\\s!?。！？]+$", "").trim();
        for (String stopword : THAI_STOPWORDS) {
            result = result.replaceAll("\\s*" + Pattern.quote(stopword) + "\\s*$", "").trim();
        }
        String lower = result.toLowerCase(Locale.ROOT);
        for (String stopword : ENGLISH_STOPWORDS) {
            if (lower.startsWith(stopword + " ")) {
                result = result.substring(stopword.length()).trim();
                lower = result.toLowerCase(Locale.ROOT);
            }
        }
        return result;
    }

    private List<String> coreTerms(String query) {
        return SearchTermTokenizer.tokenize(query);
    }

    private List<String> alternateQueries(String primary, List<String> terms, String language, String intent) {
        List<String> alternates = new ArrayList<>();
        if ("research".equals(intent)) {
            if ("th".equals(language)) {
                alternates.add(primary + " แหล่งข้อมูลทางการ");
                alternates.add(primary + " แหล่งข้อมูลปฐมภูมิ รายงาน");
            } else {
                alternates.add(primary + " official source");
                alternates.add(primary + " primary source report");
            }
        }
        if ("recommendation".equals(intent)) {
            boolean thai = primary.codePoints().anyMatch(codePoint -> codePoint >= 0x0E00 && codePoint <= 0x0E7F);
            alternates.add(primary + (thai ? " รีวิว เวลาเปิด แผนที่" : " reviews opening hours map"));
        }
        String compact = String.join(" ", terms).trim();
        if (terms.size() >= 2 && !primary.equals(compact) && !compact.equalsIgnoreCase(primary)) {
            alternates.add(compact);
        }
        String lower = primary.toLowerCase(Locale.ROOT);
        if ("comparison".equals(intent) && !lower.contains(" vs ") && !lower.contains(" versus ")) {
            String comparison = primary.replace("เปรียบเทียบ", "vs")
                    .replaceAll("(?i)\\bcompare\\b", "vs")
                    .replaceAll("\\s+", " ").trim();
            if (!comparison.equalsIgnoreCase(primary)) {
                alternates.add(comparison);
            }
        }
        if ("all".equals(language) && lower.contains("latest") && !lower.contains("official")) {
            alternates.add(primary + " official");
        }
        return alternates.stream().distinct().toList();
    }

    private boolean containsTopic(String query, String topic) {
        return query.toLowerCase(Locale.ROOT).contains(topic.toLowerCase(Locale.ROOT));
    }

    private String detectLanguage(String value) {
        boolean thai = value.codePoints().anyMatch(codePoint -> codePoint >= 0x0E00 && codePoint <= 0x0E7F);
        boolean latin = value.codePoints().anyMatch(codePoint -> (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z'));
        return thai && latin ? "all" : thai ? "th" : latin ? "en" : "all";
    }

    private String detectTimeRange(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("วันนี้") || lower.contains("ตอนนี้") || lower.contains("ล่าสุด")
                || lower.contains("today") || lower.contains("now") || lower.contains("current")
                || lower.contains("latest") || lower.contains("ข่าว")) {
            return "day";
        }
        if (lower.contains("สัปดาห์นี้") || lower.contains("this week")) {
            return "week";
        }
        return "";
    }

    private String detectIntent(
            String value,
            String timeRange,
            SearchDecision decision,
            boolean researchIntent) {
        if (decision.reason() == SearchDecisionReason.IMAGE_REQUEST) {
            return "images";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (decision.reason() == SearchDecisionReason.EXTERNAL_RESOURCE
                && RuleBasedSearchDecisionService.isLocalDiscovery(value)) {
            return "recommendation";
        }
        if (!timeRange.isBlank() || lower.contains("ข่าว") || lower.contains("news")) {
            return "current_information";
        }
        if (researchIntent) {
            return "research";
        }
        if (lower.contains("เปรียบเทียบ") || lower.contains("เทียบกับ")
                || lower.contains(" compare ") || lower.contains(" versus ") || lower.contains(" vs ")) {
            return "comparison";
        }
        if (lower.contains("ร้าน") || lower.contains("แนะนำ") || lower.contains("recommend")) {
            return "recommendation";
        }
        return "fact_lookup";
    }

    private String semanticIntent(
            String hintedIntent,
            String value,
            String timeRange,
            SearchDecision decision,
            boolean researchIntent) {
        if (decision.reason() == SearchDecisionReason.IMAGE_REQUEST) return "images";
        if ("local_discovery".equals(hintedIntent)) return "recommendation";
        if (!hintedIntent.isBlank() && !"general".equals(hintedIntent)) return hintedIntent;
        return detectIntent(value, timeRange, decision, researchIntent);
    }

    private boolean isResearchIntent(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("ค้นคว้า") || lower.contains("วิจัย") || lower.contains("เจาะลึก")
                || lower.contains("สืบค้น") || lower.contains("ตรวจสอบข้อเท็จจริง")
                || lower.contains("research") || lower.contains("investigate")
                || lower.contains("fact-check") || lower.contains("fact check")
                || lower.contains("deep dive");
    }

    private List<String> defaultRecommendationEvidence(String query) {
        List<String> evidence = new ArrayList<>(
                List.of("opening_hours", "rating", "location", "price"));
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("mrt") || lower.contains("bts") || lower.contains("สถานี")
                || lower.contains("เดินทาง") || lower.contains("transit")) {
            evidence.add("transit_access");
        }
        if (List.of("บรรยากาศ", "เงียบ", "ชิล", "ถ่ายรูป", "วิว", "โรแมนติก",
                "atmosphere", "quiet", "cozy", "vibe", "scenic", "photogenic", "romantic")
                .stream().anyMatch(lower::contains)) {
            evidence.add("atmosphere");
        }
        return List.copyOf(evidence);
    }

    private String normalize(String value) {
        return SPACE.matcher(value.replace('\u200B', ' ').replace('\uFEFF', ' ')).replaceAll(" ").trim();
    }
}
