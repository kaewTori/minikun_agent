package com.minikun.search.internal;

import com.minikun.search.SearchQueryPlanningService;
import com.minikun.search.model.SearchDecision;
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
    private static final List<String> THAI_CORE_PHRASES = List.of(
            "ร้านกาแฟ", "เปิดวันนี้", "สัปดาห์นี้", "แถว", "เชียงใหม่", "กรุงเทพ", "ล่าสุด", "ข่าว");
    private static final List<String> ENGLISH_STOPWORDS = List.of("please", "could", "you", "tell", "me", "about");
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
        increment("minikun.search.query.plan.count");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        String original = normalize(query);
        if (!decision.shouldSearch() || original.isBlank()) {
            return new SearchQueryPlan(false, original.isBlank() ? " " : original, "", List.of(), List.of(),
                    detectLanguage(original), "general", "", 1.0, "search_not_requested");
        }
        String primary = stripConversationalPrefix(original);
        primary = trimNoise(primary);
        if (primary.isBlank()) {
            primary = original;
        }
        String language = detectLanguage(primary);
        List<String> terms = coreTerms(primary, language);
        if ("th".equals(language) && terms.size() > 1) {
            primary = String.join(" ", terms);
        }
        String timeRange = detectTimeRange(primary);
        String intent = detectIntent(primary, timeRange);
        return new SearchQueryPlan(true, original, primary, alternateQueries(primary, terms).stream()
                .limit(maxAlternates).toList(), terms,
                language, intent, timeRange, 0.92, "deterministic_core_query");
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

    private List<String> coreTerms(String query, String language) {
        List<String> terms = new ArrayList<>();
        String tokenized = "th".equals(language) ? insertThaiBoundaries(query) : query;
        for (String token : SPACE.split(tokenized)) {
            String clean = token.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "").trim();
            if (!clean.isBlank() && !isStopword(clean)) {
                terms.add(clean);
            }
        }
        return List.copyOf(terms);
    }

    private String insertThaiBoundaries(String value) {
        String tokenized = value;
        for (String phrase : THAI_CORE_PHRASES.stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList()) {
            tokenized = tokenized.replace(phrase, " " + phrase + " ");
        }
        return tokenized;
    }

    private List<String> alternateQueries(String primary, List<String> terms) {
        if (terms.size() < 2 || primary.equals(String.join(" ", terms))) {
            return List.of();
        }
        return List.of(String.join(" ", terms));
    }

    private boolean isStopword(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return ENGLISH_STOPWORDS.contains(lower) || THAI_STOPWORDS.contains(token);
    }

    private String detectLanguage(String value) {
        boolean thai = value.codePoints().anyMatch(codePoint -> codePoint >= 0x0E00 && codePoint <= 0x0E7F);
        boolean latin = value.codePoints().anyMatch(codePoint -> (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z'));
        return thai && latin ? "th,en" : thai ? "th" : latin ? "en" : "all";
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

    private String detectIntent(String value, String timeRange) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (!timeRange.isBlank() || lower.contains("ข่าว") || lower.contains("news")) {
            return "current_information";
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
