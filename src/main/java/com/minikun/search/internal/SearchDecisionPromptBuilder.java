package com.minikun.search.internal;

import com.minikun.search.model.SearchDecisionPrompt;
import java.time.LocalDate;

final class SearchDecisionPromptBuilder {
    private static final String INSTRUCTIONS = """
            Classify whether the user request needs external web search.
            Do not explain your reasoning. Do not output thinking, markdown, or any text outside the JSON object.
            Decide and plan in one pass. Output exactly one object with these fields:
            {"shouldSearch":true,"reason":"EXTERNAL_RESOURCE","intent":"local_discovery",
            "confidence":0.95,"searchQuery":"ร้านอาหาร บางขุนนนท์ MRT ไฟฉาย",
            "alternateQueries":["ร้านอาหารใกล้ MRT ไฟฉาย รีวิว เวลาเปิด"],
            "evidenceNeeds":["opening_hours","rating","location","price","transit_access"],
            "location":"บางขุนนนท์ MRT ไฟฉาย"}
            Use exactly these reason values: CURRENT_INFORMATION, FACT_LOOKUP, EXTERNAL_RESOURCE, IMAGE_REQUEST,
            GENERAL_KNOWLEDGE.
            Use one intent value: local_discovery, current_information, fact_lookup, research, comparison, images,
            general.
            Use only these evidenceNeeds values: opening_hours, rating, location, price, availability,
            transit_access, official_source, freshness. Use at most 2 alternateQueries. Use [] when none and ""
            when no location. Request transit_access when the user names a station or asks how to get there.
            searchQuery must be concise and preserve named people, products, neighborhoods, landmarks, and transit
            stations verbatim. Do not invent a budget, distance, rating threshold, dietary need, or other constraint.
            For shouldSearch=false, use intent=general, searchQuery="", alternateQueries=[], evidenceNeeds=[], location="".
            CURRENT_INFORMATION, FACT_LOOKUP, EXTERNAL_RESOURCE, and IMAGE_REQUEST always require shouldSearch=true.
            GENERAL_KNOWLEDGE always requires shouldSearch=false.
            Thai requests containing explicit freshness or lookup intent such as ล่าสุด, ตอนนี้, ปัจจุบัน,
            ค้นหา, ค้นข้อมูล, เช็กข้อมูล, or ตรวจสอบข้อเท็จจริง require search.
            Recommendations for real-world businesses, restaurants, shops, venues, or services near a named
            neighborhood, landmark, or transit station require search and use EXTERNAL_RESOURCE, even when the
            user says "recommend" rather than "search". Use intent=local_discovery and request relevant evidence
            such as opening_hours, rating, location, and price because these facts can change.
            Use IMAGE_REQUEST when the user wants you to find, show, provide, or display images, photos, pictures,
            or visual references, including indirect or colloquial wording in any language. For IMAGE_REQUEST use
            intent=images, set shouldSearch=true, and make searchQuery contain only the visual subject after
            removing request words. Do not use IMAGE_REQUEST for questions about an artist or person, or for
            structural terms such as รูปแบบ, ภาพรวม, design pattern, or architecture explanation.
            Resolve short Thai follow-ups such as เรื่องเมื่อกี้, แล้วตอนนี้ล่ะ, and ช่วยเช็กให้หน่อย
            against the supplied prior conversation context.
            Never output RULE_FALLBACK.
            """.strip();

    SearchDecisionPrompt build(LocalDate currentDate, String userMessage) {
        return new SearchDecisionPrompt(INSTRUCTIONS, currentDate.toString(), userMessage);
    }

    SearchDecisionPrompt build(LocalDate currentDate, String userMessage, String conversationContext) {
        String context = conversationContext == null || conversationContext.isBlank()
                ? "No prior conversation context is available."
                : "Prior conversation context (use only to resolve references; do not search it):\n"
                        + conversationContext;
        return new SearchDecisionPrompt(INSTRUCTIONS, currentDate.toString(),
                context + "\n\nCurrent user message:\n" + userMessage);
    }
}
