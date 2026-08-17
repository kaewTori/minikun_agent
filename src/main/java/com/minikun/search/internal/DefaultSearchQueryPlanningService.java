package com.minikun.search.internal;

import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.model.SearchDecision;
import com.minikun.search.model.SearchDecisionReason;
import com.minikun.search.model.SearchQueryPlan;
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
    private static final List<String> THAI_PREFIXES = List.of(
            "ช่วยค้นหา", "ช่วยหา", "ค้นหาให้หน่อย", "อยากรู้ว่า", "ช่วยบอกหน่อยว่า", "ขอข้อมูล");
    private static final List<String> ENGLISH_PREFIXES = List.of(
            "please search for", "search for", "find out", "look up", "can you tell me", "i want to know");
    private static final List<String> THAI_STOPWORDS = List.of("หน่อย", "ให้หน่อย", "ครับ", "ค่ะ", "นะ", "ที");
    private static final List<String> ENGLISH_STOPWORDS = List.of(
            "please", "could", "you", "tell", "me", "about", "what", "is", "the", "a", "an", "for");
    private static final List<String> FOLLOW_UP_MARKERS = List.of(
            "แล้ว", "อีก", "นั้น", "นี้", "ของ", "รุ่น", "ปีนี้", "เทียบกัน", "what about", "how about",
            "and this", "that one", "this one", "also");
    private final MeterRegistry meterRegistry;
    private final int maxAlternates;

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
        String primary = stripConversationalPrefix(original);
        boolean contextual = isFollowUp(primary, conversationContext);
        if (contextual) {
            String previousUserQuery = lastUserQuery(conversationContext);
            if (!previousUserQuery.isBlank()) {
                primary = previousUserQuery + " " + primary;
            }
        }
        primary = trimNoise(primary);
        if (primary.isBlank()) {
            primary = original;
        }
        String language = detectLanguage(primary);
        List<String> terms = coreTerms(primary);
        if ("th".equals(language) && terms.size() > 1) {
            primary = String.join(" ", terms);
        }
        String timeRange = detectTimeRange(primary);
        String intent = detectIntent(primary, timeRange, decision);
        String reason = contextual ? "contextual_query" : "deterministic_core_query";
        return new SearchQueryPlan(true, original, primary, alternateQueries(primary, terms, language, intent).stream()
                .limit(maxAlternates).toList(), terms,
                language, intent, timeRange, contextual ? 0.88 : 0.92, reason);
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
                return value.substring(prefix.length()).trim();
            }
        }
        for (String prefix : ENGLISH_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return value.substring(prefix.length()).trim();
            }
        }
        return value;
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

    private boolean isFollowUp(String query, String conversationContext) {
        if (conversationContext == null || conversationContext.isBlank() || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (FOLLOW_UP_MARKERS.stream().anyMatch(lower::contains)) {
            return true;
        }
        return query.length() <= 24 && lower.matches("^(ราคา|สเปก|รุ่น|เวอร์ชัน|ปีนี้|price|spec|version|release)\\s*[?!.。！？]*$");
    }

    private String lastUserQuery(String conversationContext) {
        String[] lines = conversationContext.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (line.regionMatches(true, 0, "user:", 0, 5)) {
                String value = line.substring(5).trim();
                if (!value.isBlank()) {
                    return value.length() > 240 ? value.substring(0, 240).trim() : value;
                }
            }
        }
        return "";
    }

    private String detectLanguage(String value) {
        boolean thai = value.codePoints().anyMatch(codePoint -> codePoint >= 0x0E00 && codePoint <= 0x0E7F);
        boolean latin = value.codePoints().anyMatch(codePoint -> (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z'));
        return thai && latin ? "all" : thai ? "th" : latin ? "en" : "all";
    }

    private String detectTimeRange(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("วันนี้") || lower.contains("ล่าสุด") || lower.contains("today")
                || lower.contains("latest") || lower.contains("ข่าว")) {
            return "day";
        }
        if (lower.contains("สัปดาห์นี้") || lower.contains("this week")) {
            return "week";
        }
        return "";
    }

    private String detectIntent(String value, String timeRange, SearchDecision decision) {
        if (decision.reason() == SearchDecisionReason.IMAGE_REQUEST) {
            return "images";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!timeRange.isBlank() || lower.contains("ข่าว") || lower.contains("news")) {
            return "current_information";
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

    private String normalize(String value) {
        return SPACE.matcher(value.replace('\u200B', ' ').replace('\uFEFF', ' ')).replaceAll(" ").trim();
    }
}
