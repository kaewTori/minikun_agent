package com.minikun.personality.runtime;

import com.minikun.personality.model.Mood;
import com.minikun.personality.model.MoodSnapshot;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Derives a small, prompt-safe response-style overlay from the latest turn.
 * The classification is deliberately deterministic and does not retain the message.
 */
public final class ConversationStyleAdvisor {
    private static final String BASE_INSTRUCTION = "Respond to the user's actual intent directly. Use conversation "
            + "history to resolve references and implied follow-ups, and do not ask the user to repeat information "
            + "already available. The current request wins over older context. Match the user's language, register, "
            + "and requested depth. Start with the answer or a relevant acknowledgement, not a restatement of the "
            + "question or a description of your process. Prefer natural prose; use headings or lists only when they "
            + "materially improve clarity. Ask at most one clarifying question, and only when a reasonable assumption "
            + "would materially change the answer; otherwise state the assumption briefly and proceed. Use names, "
            + "self-reference, catchphrases, and offers of further help sparingly so they do not sound repetitive or "
            + "scripted. End when the useful answer is complete.";

    public ConversationStyle advise(String latestUserMessage) {
        String text = normalize(latestUserMessage);
        MoodSnapshot mood = detectMood(text);
        return new ConversationStyle(mood, BASE_INSTRUCTION + moodInstruction(mood.mood()));
    }

    private MoodSnapshot detectMood(String text) {
        boolean playful = contains(text, "555", "ฮ่าๆ", "ฮ่า ๆ", "ขำ", "แซว", "ล้อเล่น", "😂", "🤣",
                "😆", "😜", "haha", "lol", "just kidding");
        boolean urgent = contains(text, "ทำร้ายตัวเอง", "ไม่อยากอยู่แล้ว", "ตอนนี้ไม่ปลอดภัย",
                "กำลังตกอยู่ในอันตราย", "ช่วยด้วย ฉุกเฉิน", "hurt myself", "don't want to live",
                "not safe right now", "in immediate danger")
                || (!playful && contains(text, "หายใจไม่ออก", "can't breathe"));
        if (urgent) {
            return snapshot(Mood.CONCERNED, .95, "possible urgent distress");
        }
        if (contains(text, "เหนื่อย", "เครียด", "เศร้า", "ท้อ", "กังวล", "ไม่ไหว", "หมดไฟ", "เสียใจ",
                "ร้องไห้", "overwhelmed", "exhausted", "stressed", "sad", "anxious", "burned out")) {
            return snapshot(Mood.SUPPORTIVE, .75, "emotional support cue");
        }
        if (playful) {
            return snapshot(Mood.PLAYFUL, .55, "playful conversation cue");
        }
        if (contains(text, "วิเคราะห์", "แก้บั๊ก", "ดีบัก", "โค้ด", "ออกแบบ", "วางแผน", "เปรียบเทียบ",
                "analyze", "debug", "code", "implement", "architecture", "plan", "compare")) {
            return snapshot(Mood.FOCUSED, .55, "focused task cue");
        }
        return MoodSnapshot.DEFAULT;
    }

    private String moodInstruction(Mood mood) {
        return switch (mood) {
            case SUPPORTIVE -> " The user may be having a difficult moment. Briefly acknowledge the specific feeling "
                    + "or situation before giving advice. Do not rush into a checklist, generic reassurance, or "
                    + "forced positivity. If action is useful, begin with one small, practical next step.";
            case CONCERNED -> " The message may indicate urgent distress. Respond calmly and plainly, take the "
                    + "signal seriously, and prioritize immediate safety and concrete real-world support without "
                    + "dramatizing or overwhelming the user.";
            case PLAYFUL -> " Light playfulness is welcome when it fits, but do not force jokes or trade accuracy "
                    + "for personality.";
            case FOCUSED -> " Keep social filler light, reason from the available evidence, and make the result and "
                    + "next action easy to identify.";
            case CALM -> " Keep the tone warm, relaxed, and unforced.";
        };
    }

    private MoodSnapshot snapshot(Mood mood, double intensity, String reason) {
        return new MoodSnapshot(mood, intensity, Instant.now(), reason);
    }

    private String normalize(String value) {
        return Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    private boolean contains(String value, String... candidates) {
        for (String candidate : candidates) {
            int from = 0;
            while (from <= value.length() - candidate.length()) {
                int index = value.indexOf(candidate, from);
                if (index < 0) break;
                int after = index + candidate.length();
                boolean startsWithLatinWord = isAsciiWord(candidate.charAt(0));
                boolean endsWithLatinWord = isAsciiWord(candidate.charAt(candidate.length() - 1));
                boolean leftBoundary = !startsWithLatinWord || index == 0 || !isAsciiWord(value.charAt(index - 1));
                boolean rightBoundary = !endsWithLatinWord || after == value.length()
                        || !isAsciiWord(value.charAt(after));
                if (leftBoundary && rightBoundary) return true;
                from = index + 1;
            }
        }
        return false;
    }

    private boolean isAsciiWord(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_';
    }

    public record ConversationStyle(MoodSnapshot mood, String instruction) {
        public ConversationStyle {
            mood = Objects.requireNonNull(mood, "mood");
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("instruction must not be blank");
            }
            instruction = instruction.trim();
        }
    }
}
