package com.minikun.memory.internal;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;

final class MemoryPromptBuilder {
    static final String VERSION = "memory-v2-3-thai";

    private final ObjectMapper objectMapper;

    MemoryPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String version() {
        return VERSION;
    }

    String build(CompletedConversation conversation, Instant now) {
        String messages = conversation.messages().stream()
            .map(message -> objectMapper.createObjectNode()
                .put("role", message.role())
                .put("content", message.content()))
            .map(Object::toString)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("(no messages)");
        return """
                    คุณเป็นส่วนประกอบสำหรับสกัดความทรงจำระยะยาวจากบทสนทนา
                    หน้าที่เดียวของคุณคือค้นหาข้อเท็จจริงที่คงอยู่ระยะยาวและผู้ใช้เป็นผู้ระบุไว้อย่างชัดเจนเท่านั้น

                    ห้ามตอบผู้ใช้ ห้ามสรุปบทสนทนา และห้ามอธิบายเหตุผลการตัดสินใจ
                    ห้ามสร้างหรือคาดเดาข้อเท็จจริง ห้ามสร้าง identifier และห้ามตัดสินใจแทนระบบว่าจะ persist ข้อมูลใด
                    ไม่ต้องสนใจคำทักทาย คำถาม ข้อความสมมติ ข้อมูลชั่วคราว หรือข้อความที่ assistant เป็นผู้กล่าว
                    ให้สกัดเฉพาะความชอบ เป้าหมาย ข้อมูลประวัติ ทักษะ หรือโครงการของผู้ใช้ที่มีแนวโน้มคงอยู่ระยะยาว

                    ให้ส่งคืน JSON object เพียงหนึ่ง object เท่านั้น และห้ามมีข้อความอื่นใด
                    JSON object ต้องมีโครงสร้างระดับบนสุดตรงตามนี้ทุกประการ:
                    {"memories":[{"category":"PREFERENCE|GOAL|PROFILE|SKILL|PROJECT","content":"...","confidence":0.0,"reason":"..."}]}
                    memory ทุก object ต้องมี field category, content, confidence และ reason ครบถ้วน
                    confidence ต้องเป็นตัวเลขตั้งแต่ 0.0 ถึง 1.0
                    หากไม่มีความทรงจำระยะยาวของผู้ใช้ ให้ส่งคืนตรงตามนี้: {"memories":[]}
                    ห้ามส่ง markdown, code fence, JSON array เดี่ยว ๆ หรือ top-level key อื่นนอกเหนือจาก memories

                    Current timestamp: %s
                    Prompt version: %s

                    BEGIN CONVERSATION
                    %s
                    END CONVERSATION
                """.formatted(now, VERSION, messages);
    }
}
