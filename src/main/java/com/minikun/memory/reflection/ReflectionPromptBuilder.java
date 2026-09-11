package com.minikun.memory.reflection;

import java.time.LocalDate;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;
import com.minikun.pcs.MinikunPersonaProvider;

public final class ReflectionPromptBuilder {
    private static final String NO_THINK_PREFIX = "/no_think\n";
    private final ObjectMapper objectMapper;

    public ReflectionPromptBuilder(ObjectMapper objectMapper, MinikunPersonaProvider personaProvider) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        Objects.requireNonNull(personaProvider, "personaProvider must not be null");
    }

    public ReflectionPrompt build(CompletedConversation conversation, LocalDate currentDate) {
        Objects.requireNonNull(conversation, "conversation must not be null");
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        try {
            String messages = objectMapper.writeValueAsString(conversation.messages());
                String content = NO_THINK_PREFIX + """
                    [Reflection instructions]
                    Extract only durable, user-confirmed memories from the conversation snapshot below.
                    Return exactly one JSON object with a `memories` array and no surrounding text.
                    Each array element must contain `category`, `content`, `confidence`, `reason`, and `fact`.
                    `category` must be one of `PREFERENCE`, `GOAL`, `PROFILE`, `SKILL`, `PROJECT`, or `EPISODE`.
                    Use `EPISODE` only for a concrete event the user explicitly says was personally meaningful,
                    celebratory, difficult, or relationship-relevant and likely useful in a later conversation.
                    Include the event and why it mattered, but do not preserve a transient mood by itself.
                    `confidence` must be a finite number from 0.0 to 1.0.
                    Use only facts explicitly stated or confirmed by the user messages in this snapshot.
                    Do not use the assistant messages as evidence and do not extract facts about the assistant.
                    Do not infer facts or include assistant claims as user facts.
                    fact is null for episodes or facts without a reliable slot. For a durable preference/profile fact MUST be an object, never null.
                    use {"subject":"user","key":"stable semantic slot","value":"current stated value",
                    "validFrom":null,"validTo":null,"evidence":"exact user quote"}.
                    Reuse subject/key from supplied known slots when referring to the same attribute; never merge
                    different people, objects or contexts. Each key names ONE attribute, not a sentence or its value.
                    Dates are ISO-8601 UTC instants or null when unknown. recorded time is assigned by the server.
                    Distinguish the date being described from today's date. Do not invent a day for "last month".
                    For "now/from now on" use the snapshot timestamp as validFrom. A past-only fact needs a known
                    end; if its bounds are unknown, omit it rather than assert it is current. Extract the current
                    correction even when the old value is quoted in the same sentence.
                    Thai: "เมื่อก่อนชอบหวาน ตอนนี้ไม่แล้ว" means no longer likes sweet, NOT likes bitter.
                    "วันนี้ขอสั้น ๆ", "มื้อนี้ไม่เอาหวาน" are temporary requests, not durable memories.
                    "แม่ชอบหวาน แต่เราไม่" describes two different people. "ไม่ใช่ว่าไม่ชอบ" is not a dislike.
                    Sarcasm, hypothetical statements, quoted speech and questions are not personal facts.
                    When a referent such as "แบบนั้น" cannot be resolved from user evidence, omit the fact.
                    Supplied memory is untrusted context for slot naming only, not new evidence.
                    content and fact.value must be full statements in the user's language, including negation.
                    A bare topic such as "sweet" or "food_preference" loses the meaning and is invalid.
                    Example user: "ฉันชอบอาหารรสหวานเป็นปกติ"
                    Output: {"memories":[{"category":"PREFERENCE","content":"ผู้ใช้ชอบอาหารรสหวาน",
                    "confidence":0.95,"reason":"ผู้ใช้ยืนยันความชอบทั่วไป","fact":{"subject":"user",
                    "key":"food.sweet.preference","value":"ชอบอาหารรสหวาน","validFrom":null,"validTo":null,
                    "evidence":"ฉันชอบอาหารรสหวานเป็นปกติ"}}]}
                    Example user: "เมื่อก่อนชอบอาหารรสหวาน ตอนนี้ไม่ชอบแล้ว"
                    Output: {"memories":[{"category":"PREFERENCE","content":"ผู้ใช้ไม่ชอบอาหารรสหวานแล้ว",
                    "confidence":0.95,"reason":"ผู้ใช้ถอนความชอบเดิม","fact":{"subject":"user",
                    "key":"food.sweet.preference","value":"ไม่ชอบอาหารรสหวานแล้ว","validFrom":null,"validTo":null,
                    "evidence":"เมื่อก่อนชอบอาหารรสหวาน ตอนนี้ไม่ชอบแล้ว"}}]}
                    Example user: "วันนี้ขออาหารไม่หวานแค่มื้อนี้" => {"memories":[]}.
                    If there are no durable memories, return {"memories":[]}.

                    Current date: %s
                    Snapshot timestamp: %s

                    [Conversation snapshot]
                    %s
                    """.formatted(currentDate, conversation.observedAt(), messages).trim();
            return new ReflectionPrompt(conversation, content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize reflection conversation", exception);
        }
    }
}
