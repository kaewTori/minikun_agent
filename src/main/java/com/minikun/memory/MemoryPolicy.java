package com.minikun.memory;

import java.util.Locale;

import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;

public record MemoryPolicy(
        double minimumConfidence,
        int maximumContentLength) {
    public MemoryPolicy {
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0.0 || minimumConfidence > 1.0) {
            throw new IllegalArgumentException("minimum confidence must be between 0 and 1");
        }
        if (maximumContentLength < 1) {
            throw new IllegalArgumentException("maximum content length must be positive");
        }
    }

    public static MemoryPolicy defaults() {
        return new MemoryPolicy(0.7, 500);
    }

    public boolean accepts(CandidateMemory candidate) {
        return accepts(candidate, null);
    }

    public boolean accepts(CandidateMemory candidate, CompletedConversation conversation) {
        return candidate.confidence() >= minimumConfidence
                && candidate.content().length() <= maximumContentLength
                && !isTemporary(candidate.content())
                && !isQuestionOrRequest(candidate.content())
                && !isConversationSpecific(candidate.content())
                && hasContextForShortContent(candidate.content(), conversation);
    }

    /** Explicit turn-only scope must never become a lasting preference, even if the extractor says otherwise. */
    public static boolean temporaryPreference(String evidence) {
        return evidence != null && java.util.regex.Pattern.compile(
                "(?:แค่|เฉพาะ)(?:วันนี้|ครั้งนี้|คราวนี้|มื้อนี้|คำตอบนี้)|(?:วันนี้|ครั้งนี้|มื้อนี้).*(?:ขอ|ไม่เอา|ไม่ต้อง)"
                + "|(?:ขอ|ไม่เอา|ไม่ต้อง).*(?:วันนี้|ครั้งนี้|มื้อนี้)|for this (?:answer|turn|meal)|today only|just (?:today|this time)",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(evidence).find();
    }

    private boolean isTemporary(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        return normalized.matches(".*\\b(today|tonight|this week|for now|just this time|ชั่วคราว|วันนี้|คืนนี้|ครั้งนี้|คราวนี้|มื้อนี้)\\b.*");
    }

    private boolean isQuestionOrRequest(String content) {
        String normalized = content.strip().toLowerCase(Locale.ROOT);
        return normalized.endsWith("?")
                || normalized.endsWith("？")
                || normalized.matches("^(what|why|how|which|who|where|when|can you|could you|please|tell me)\\b.*")
                || normalized.matches("^(อะไร|อะไรคือ|คืออะไร|ทำไม|อย่างไร|ยังไง|ที่ไหน|เมื่อไหร่|ไหม|หรือยัง|ช่วย|ขอ|แนะนำ|บอก|อธิบาย).*" );
    }

    private boolean isConversationSpecific(String content) {
        String normalized = content.strip().toLowerCase(Locale.ROOT);
        return normalized.matches(".*(อยากกิน|หา(ร้าน|อาหาร)|ร้านอาหาร|เมนู|มื้อ|ซุป|ราเมง|ราคาไม่เกี่ยง|บรรยากาศอะไรก็ได้|อะไรก็ได้).*" )
                || normalized.matches(".*(for this meal|for this search|restaurant|menu|ramen|food|price|atmosphere).*" )
                || normalized.matches(".*(โปรเจค|โครงการ|project).*(เกี่ยวกับ ai|เกี่ยวกับเอไอ|about ai|ai project)\\s*$");
    }

    private boolean hasContextForShortContent(String content, CompletedConversation conversation) {
        if (conversation == null || content.strip().isEmpty() || content.strip().split("\\s+").length > 1) {
            return true;
        }
        String normalizedContent = content.strip().toLowerCase(Locale.ROOT);
        return conversation.messages().stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .map(CompletedConversation.Message::content)
                .map(String::strip)
                .filter(message -> !message.equalsIgnoreCase(content.strip()))
                .filter(message -> !isQuestionOrRequest(message))
                .map(message -> message.toLowerCase(Locale.ROOT))
                .anyMatch(message -> message.contains(normalizedContent));
    }
}
