package com.minikun.memory.internal;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;

final class MemoryPromptBuilder {
    static final String VERSION = "memory-v2-8-general-thai";

    private final ObjectMapper objectMapper;

    MemoryPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String version() {
        return VERSION;
    }

    String build(CompletedConversation conversation, Instant now) {
        String messages = conversation.messages().stream()
            .filter(message -> "user".equalsIgnoreCase(message.role()))
            .map(message -> objectMapper.createObjectNode()
                .put("role", message.role())
                .put("content", message.content()))
            .map(Object::toString)
            .reduce((left, right) -> left + "\n" + right)
            .orElse("(no user messages)");
        return """
                    คุณเป็นส่วนประกอบสำหรับสกัดความทรงจำระยะยาวจากบทสนทนา
                    หน้าที่เดียวของคุณคือค้นหาข้อเท็จจริงที่คงอยู่ระยะยาวและผู้ใช้เป็นผู้ระบุไว้อย่างชัดเจนเท่านั้น

                    ห้ามตอบผู้ใช้ ห้ามสรุปบทสนทนา และห้ามอธิบายเหตุผลการตัดสินใจ
                    ห้ามสร้างหรือคาดเดาข้อเท็จจริง ห้ามสร้าง identifier และห้ามตัดสินใจแทนระบบว่าจะ persist ข้อมูลใด
                    ไม่ต้องสนใจคำทักทาย คำถาม ข้อความสมมติ ข้อมูลชั่วคราว หรือข้อความที่ assistant เป็นผู้กล่าว
                    ให้สกัดเฉพาะความชอบ เป้าหมาย ข้อมูลประวัติ ทักษะ หรือโครงการของผู้ใช้ที่มีแนวโน้มคงอยู่ระยะยาว
                    ข้อความของ assistant ไม่ใช่ข้อเท็จจริงของผู้ใช้ เว้นแต่ผู้ใช้จะยืนยันข้อเท็จจริงนั้นอย่างชัดเจน
                    ให้คงรายละเอียดสำคัญจากข้อความของ user ไว้ เช่น ชื่อ รุ่น จำนวน ข้อจำกัด เครื่องมือ และสิ่งที่ผู้ใช้กำลังทำ
                    ห้ามสรุปจนรายละเอียดหาย ห้ามเปลี่ยนข้อเท็จจริงเป็นเป้าหมาย และห้ามสร้าง content ที่ไม่ได้อยู่ในข้อความของ user
                    เลือก category ตามความหมายของข้อความ ไม่ใช่เลือกจากคำกริยาเพียงคำเดียว:
                    PROFILE คือข้อมูลเกี่ยวกับผู้ใช้หรือสภาพแวดล้อมของผู้ใช้
                    PREFERENCE คือสิ่งที่ผู้ใช้ชอบ เลือกใช้ หรือมีความต้องการด้านรูปแบบ
                    GOAL คือสิ่งที่ผู้ใช้บอกว่าอยากทำ ต้องการทำ หรือกำลังมุ่งไปให้สำเร็จ
                    SKILL คือความสามารถหรือประสบการณ์ที่ผู้ใช้บอกว่ามี
                    PROJECT คือโครงการหรืองานที่ผู้ใช้กำลังทำหรือมีส่วนร่วม
                    หากข้อความมีหลายความหมาย ให้เก็บเฉพาะความหมายที่ระบุชัดที่สุด และอย่าฝืนสร้างหลาย memory
                    สำหรับข้อเท็จจริงที่ user ระบุโดยตรง ให้ confidence เป็น 0.9 และ reason เป็น "ผู้ใช้ระบุโดยตรง"
                    หากไม่มีข้อความจาก user ที่ระบุข้อเท็จจริงระยะยาวอย่างชัดเจน ให้ตอบตรงตามนี้ทันที: {"memories":[]}

                    ให้ส่งคืน JSON object เพียงหนึ่ง object เท่านั้น และห้ามมีข้อความอื่นใด
                    JSON object ต้องมีโครงสร้างระดับบนสุดตรงตามนี้ทุกประการ:
                    {"memories":[{"category":"PREFERENCE|GOAL|PROFILE|SKILL|PROJECT","content":"...","confidence":0.0,"reason":"..."}]}
                    memory ทุก object ต้องมี field category, content, confidence และ reason ครบถ้วน
                    confidence ต้องเป็นตัวเลขตั้งแต่ 0.0 ถึง 1.0
                    content และ reason ต้องเป็นข้อความสั้นกระชับ ไม่เกินหนึ่งประโยค และต้อง escape เครื่องหมาย double quote ตาม JSON
                    หากไม่มีความทรงจำระยะยาวของผู้ใช้ ให้ส่งคืนตรงตามนี้: {"memories":[]}
                    ห้ามส่ง markdown, code fence, JSON array เดี่ยว ๆ หรือ top-level key อื่นนอกเหนือจาก memories

                    Current timestamp: %s
                    Prompt version: %s

                    BEGIN USER MESSAGES
                    %s
                    END USER MESSAGES
                """.formatted(now, VERSION, messages);
    }
}
