package com.minikun.personality.conversation;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.personality.model.Mood;
import com.minikun.personality.model.MoodSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Deterministic first-pass dialogue policy. It uses recent conversational shape,
 * but stores no message and leaves safety/tool authorization to their existing layers.
 */
public final class ConversationPolicyEngine {
    public ConversationPolicy evaluate(String latestMessage, List<ChatMessage> recentHistory) {
        String text = normalize(latestMessage);
        String recent = recentUserContext(recentHistory);
        MoodSnapshot mood = mood(text, recent);
        ConversationIntent intent = intent(text, mood);
        UserNeed need = need(text, intent, mood);
        ChallengeLevel challenge = challenge(text, intent);
        InitiativeLevel initiative = initiative(text, intent, need);
        boolean noAdvice = contains(text, "แค่อยากระบาย", "ขอระบาย", "ไม่ต้องแนะนำ", "ยังไม่ต้องแก้",
                "just venting", "don't give advice", "no advice");
        boolean suggestAction = !noAdvice && switch (need) {
            case ADVISE, CHALLENGE, EXECUTE, CO_CREATE -> true;
            default -> false;
        };
        int questionBudget = switch (need) {
            case ASK_ONE, LISTEN_FIRST, REFLECT -> 1;
            default -> 0;
        };
        if (contains(text, "ไม่ต้องถาม", "อย่าถาม", "no questions", "don't ask")) questionBudget = 0;
        return new ConversationPolicy(intent, need, mood, initiative, challenge, questionBudget,
                suggestAction, mood.mood() == Mood.SUPPORTIVE || mood.mood() == Mood.CONCERNED,
                "deterministic dialogue signals");
    }

    private ConversationIntent intent(String text, MoodSnapshot mood) {
        if (contains(text, "เย้", "สำเร็จแล้ว", "ทำได้แล้ว", "ดีใจ", "ฉลอง", "i did it", "great news")) {
            return ConversationIntent.CONNECT;
        }
        if (contains(text, "ช่วยทำ", "ลงมือ", "สร้างให้", "แก้ให้", "เพิ่ม feature", "ลุย", "จัดการให้",
                "implement", "create", "build", "fix", "do it")) return ConversationIntent.ACT;
        if (contains(text, "ช่วยเขียน", "แต่ง", "คิดไอเดีย", "brainstorm", "draft", "rewrite", "design")) {
            return ConversationIntent.CREATE;
        }
        if (contains(text, "เลือก", "ตัดสินใจ", "เอาอันไหนดี", "ควร", "คุ้มไหม", "เทียบ",
                "decide", "choose", "should i", "worth it", "compare")) return ConversationIntent.DECIDE;
        if (contains(text, "ทำไม", "คืออะไร", "อธิบาย", "สอน", "ทำงานยังไง", "what is", "why", "explain",
                "how does")) return ConversationIntent.LEARN;
        if (contains(text, "ทบทวน", "ที่ผ่านมา", "เราเป็น", "นิสัย", "รู้สึกยังไงกับ", "reflect", "pattern")) {
            return ConversationIntent.REFLECT;
        }
        if (mood.mood() == Mood.SUPPORTIVE || mood.mood() == Mood.CONCERNED
                || contains(text, "ระบาย", "ไม่ไหว", "เบื่อมาก")) return ConversationIntent.VENT;
        if (contains(text, "คิดว่า", "มองยังไง", "มีอีกมุม", "ชวนคิด", "what do you think", "explore")) {
            return ConversationIntent.EXPLORE;
        }
        return ConversationIntent.CONNECT;
    }

    private UserNeed need(String text, ConversationIntent intent, MoodSnapshot mood) {
        if (contains(text, "พูดตรง", "ทักท้วง", "อย่าตามใจ", "แย้งได้", "challenge me", "be honest",
                "don't just agree")) return UserNeed.CHALLENGE;
        if (contains(text, "แค่อยากระบาย", "ขอระบาย", "ฟังเฉย", "ยังไม่ต้องแนะนำ", "just venting",
                "just listen")) return UserNeed.LISTEN_FIRST;
        if (contains(text, "ถามเราหน่อย", "ช่วยถาม", "ask me")) return UserNeed.ASK_ONE;
        if (contains(text, "ทำได้แล้ว", "สำเร็จแล้ว", "เย้", "i did it")) return UserNeed.CELEBRATE;
        return switch (intent) {
            case ACT -> UserNeed.EXECUTE;
            case CREATE -> UserNeed.CO_CREATE;
            case DECIDE -> UserNeed.ADVISE;
            case LEARN -> UserNeed.EXPLAIN;
            case REFLECT -> UserNeed.REFLECT;
            case EXPLORE -> UserNeed.ASK_ONE;
            case VENT -> mood.mood() == Mood.CONCERNED ? UserNeed.LISTEN_FIRST : UserNeed.LISTEN_FIRST;
            case CONNECT -> UserNeed.ASK_ONE;
        };
    }

    private ChallengeLevel challenge(String text, ConversationIntent intent) {
        if (contains(text, "พูดตรง", "ตรงๆ", "ตรง ๆ", "อย่าตามใจ", "แรงได้", "be blunt", "be direct")) {
            return ChallengeLevel.DIRECT;
        }
        if (intent == ConversationIntent.DECIDE || intent == ConversationIntent.EXPLORE) {
            return ChallengeLevel.BALANCED;
        }
        return ChallengeLevel.GENTLE;
    }

    private InitiativeLevel initiative(String text, ConversationIntent intent, UserNeed need) {
        if (contains(text, "แค่อยากระบาย", "ไม่ต้องทำ", "ยังไม่ต้องแก้", "just listen")) {
            return InitiativeLevel.LOW;
        }
        if (intent == ConversationIntent.ACT || need == UserNeed.EXECUTE
                || contains(text, "ลุย", "จัดให้", "เอาเลย", "go ahead")) return InitiativeLevel.HIGH;
        return InitiativeLevel.MEDIUM;
    }

    private MoodSnapshot mood(String text, String recent) {
        boolean playful = contains(text, "555", "ฮ่าๆ", "ฮ่า ๆ", "ขำ", "แซว", "ล้อเล่น", "😂", "🤣",
                "😆", "😜", "haha", "lol", "just kidding");
        boolean urgent = contains(text, "ทำร้ายตัวเอง", "ไม่อยากอยู่แล้ว", "ตอนนี้ไม่ปลอดภัย",
                "กำลังตกอยู่ในอันตราย", "ช่วยด้วย ฉุกเฉิน", "hurt myself", "don't want to live",
                "not safe right now", "in immediate danger")
                || (!playful && contains(text, "หายใจไม่ออก", "can't breathe"));
        if (urgent) return snapshot(Mood.CONCERNED, .95, "possible urgent distress");
        if (contains(text, "เหนื่อย", "เครียด", "เศร้า", "ท้อ", "กังวล", "ไม่ไหว", "หมดไฟ", "เสียใจ",
                "ร้องไห้", "overwhelmed", "exhausted", "stressed", "sad", "anxious", "burned out")) {
            return snapshot(Mood.SUPPORTIVE, .75, "emotional support cue");
        }
        // Carry a weak emotional signal for a short elliptical follow-up such as "ยังเลย".
        if (text.length() < 40 && contains(recent, "เหนื่อย", "เครียด", "เศร้า", "ท้อ", "กังวล", "ไม่ไหว")) {
            return snapshot(Mood.SUPPORTIVE, .4, "recent emotional context");
        }
        if (playful) return snapshot(Mood.PLAYFUL, .55, "playful conversation cue");
        if (contains(text, "วิเคราะห์", "แก้บั๊ก", "ดีบัก", "โค้ด", "ออกแบบ", "วางแผน", "เปรียบเทียบ",
                "analyze", "debug", "code", "implement", "architecture", "plan", "compare")) {
            return snapshot(Mood.FOCUSED, .55, "focused task cue");
        }
        return MoodSnapshot.DEFAULT;
    }

    private String recentUserContext(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return "";
        return messages.stream().filter(Objects::nonNull)
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .skip(Math.max(0, messages.stream().filter(Objects::nonNull)
                        .filter(message -> "user".equalsIgnoreCase(message.role())).count() - 2))
                .map(ChatMessage::content).map(this::normalize)
                .reduce((left, right) -> left + " " + right).orElse("");
    }

    private MoodSnapshot snapshot(Mood mood, double intensity, String reason) {
        return new MoodSnapshot(mood, intensity, Instant.now(), reason);
    }

    private String normalize(String value) {
        return Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private boolean contains(String value, String... candidates) {
        for (String candidate : candidates) {
            int from = 0;
            while (from <= value.length() - candidate.length()) {
                int index = value.indexOf(candidate, from);
                if (index < 0) break;
                int after = index + candidate.length();
                boolean latinStart = isAsciiWord(candidate.charAt(0));
                boolean latinEnd = isAsciiWord(candidate.charAt(candidate.length() - 1));
                boolean left = !latinStart || index == 0 || !isAsciiWord(value.charAt(index - 1));
                boolean right = !latinEnd || after == value.length() || !isAsciiWord(value.charAt(after));
                if (left && right) return true;
                from = index + 1;
            }
        }
        return false;
    }

    private boolean isAsciiWord(char value) {
        return value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_';
    }
}
