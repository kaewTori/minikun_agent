package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSelection;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.search.GroundingIntent;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps unsupported source wording out of both complete and streamed answers. */
final class GroundedAnswerGuard {
    static final String NO_SOURCE = "ยังไม่มีข้อความต้นฉบับที่ตรวจสอบได้ จึงไม่อยากยกหรือแปลข้อความจากความจำแล้วบอกว่าเป็นต้นฉบับ ส่งลิงก์แหล่งข้อมูลหรือข้อความที่ต้องการให้ตรวจได้ครับ";
    private static final Pattern QUOTED = Pattern.compile("[\"“]([^\"”\\r\\n]{40,})[\"”]");
    private static final Pattern SOURCE_HEADING = Pattern.compile(
            "(?i)^\\[(?:verse|chorus|bridge|intro|outro)[^]]*]$");
    private static final Pattern COMMENTARY_LINE = Pattern.compile(
            "(?iu)^(?:คำแปล|วิเคราะห์|การตีความ|translation|analysis|interpretation)[:：].*");

    private GroundedAnswerGuard() { }

    static boolean required(String query, String conversationContext) {
        return GroundingIntent.requiresSource(query, conversationContext);
    }

    static String beforeModel(String query, String conversationContext, KnowledgeSelection knowledge) {
        if (!required(query, conversationContext)) return null;
        if (GroundingIntent.transformsProvidedText(query)) return null;
        return sources(knowledge).isEmpty() ? NO_SOURCE : null;
    }

    static String afterModel(String answer, KnowledgeSelection knowledge, String query) {
        List<KnowledgeCandidate> sources = sources(knowledge);
        boolean providedText = GroundingIntent.transformsProvidedText(query);
        if (sources.isEmpty() && !providedText) return NO_SOURCE;
        String evidence = normalize(sources.stream().map(KnowledgeCandidate::content)
                .reduce((left, right) -> left + " " + right).orElse("")
                + (providedText ? " " + query : ""));
        Matcher quotes = QUOTED.matcher(answer);
        while (quotes.find()) {
            String passage = normalize(quotes.group(1));
            if (passage.codePoints().anyMatch(Character::isLetter)
                    && !evidence.contains(passage)) return NO_SOURCE;
        }
        boolean sourceBlock = false;
        for (String rawLine : answer.split("\\R")) {
            String line = rawLine.strip();
            if (SOURCE_HEADING.matcher(line).matches()) {
                sourceBlock = true;
                continue;
            }
            if (line.isEmpty() || line.startsWith("[")) {
                sourceBlock = false;
                continue;
            }
            if (sourceBlock && COMMENTARY_LINE.matcher(line).matches()) {
                sourceBlock = false;
                continue;
            }
            String passage = normalize(line);
            if (sourceBlock && passage.length() >= 10
                    && passage.codePoints().anyMatch(Character::isLetter)
                    && !evidence.contains(passage)) return NO_SOURCE;
        }
        return answer;
    }

    private static List<KnowledgeCandidate> sources(KnowledgeSelection knowledge) {
        if (knowledge == null) return List.of();
        return knowledge.selectedCandidates().stream()
                .filter(candidate -> candidate.source() == KnowledgeSource.BROWSER
                        || candidate.source() == KnowledgeSource.PERSONAL)
                .toList();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("\\s+", " ").strip();
    }
}
