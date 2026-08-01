package com.minikun.memory.internal;

import java.time.Instant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;

final class MemoryPromptBuilder {
    static final String VERSION = "memory-v3-1-evidence-thai";

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
                    หน้าที่เดียวของคุณคือค้นหาข้อเท็จจริงที่คงอยู่ระยะยาวและมีหลักฐานจากข้อความของ user เท่านั้น

                    ห้ามตอบผู้ใช้ ห้ามสรุปบทสนทนา และห้ามอธิบายเหตุผลการตัดสินใจ
                    ห้ามสร้างหรือคาดเดาข้อเท็จจริง ห้ามสร้าง identifier และห้ามตัดสินใจแทนระบบว่าจะ persist ข้อมูลใด
                    ไม่ต้องสนใจคำทักทาย คำถาม คำขอ ข้อความสมมติ ข้อมูลชั่วคราว หรือข้อความที่ assistant เป็นผู้กล่าว
                    ให้สกัดเฉพาะความชอบ เป้าหมาย ข้อมูลประวัติ ทักษะ หรือโครงการของผู้ใช้ที่มีแนวโน้มคงอยู่ระยะยาว
                    ข้อความของ assistant ไม่ใช่ข้อเท็จจริงของผู้ใช้ เว้นแต่ผู้ใช้จะยืนยันข้อเท็จจริงนั้นอย่างชัดเจน
                    ให้คงรายละเอียดสำคัญจากข้อความของ user ไว้ เช่น ชื่อ รุ่น จำนวน ข้อจำกัด เครื่องมือ และสิ่งที่ผู้ใช้กำลังทำ
                    ห้ามสรุปจนรายละเอียดหาย ห้ามเปลี่ยนข้อเท็จจริงเป็นเป้าหมาย และห้ามสร้าง content ที่ไม่ได้อยู่ในข้อความของ user
                    memory ที่รับได้ต้องเป็นข้อความบอกเล่าหรือการยืนยันของ user ซึ่งสื่อข้อเท็จจริง ความชอบ เป้าหมาย ทักษะ หรือโครงการอย่างชัดเจน
                    คำถามไม่ใช่ memory แม้คำถามนั้นจะกล่าวถึงโปรเจค ความชอบ หรือข้อมูลของผู้ใช้ เช่น "โปรเจคที่เราทำอยู่คือโปรเจคอะไรหรอ?" ต้องตอบเป็น memories ว่าง
                    คำขอให้ช่วย คำขอให้แนะนำ หรือคำขอให้ค้นหาข้อมูลไม่ใช่ memory
                    คำตอบสั้นหรือคำเดี่ยว เช่น "mac" ให้เก็บได้ก็ต่อเมื่อมีบริบทในข้อความของ user ที่ยืนยันความหมาย เช่น "ฉันใช้ mac เป็นเครื่องหลัก"
                    หากมีเพียงคำเดี่ยวหรือ fragment ที่ไม่มีบริบทเพียงพอ เช่น user พิมพ์แค่ "mac" ให้ตอบเป็น memories ว่าง
                    ห้ามถือว่าคำที่ user พูดถึงเป็นข้อมูลของ user โดยอัตโนมัติ ต้องแยกการถามถึงสิ่งหนึ่งออกจากการยืนยันสิ่งนั้น
                    เลือก category ตามความหมายของข้อความ ไม่ใช่เลือกจากคำกริยาเพียงคำเดียว:
                    PROFILE คือข้อมูลเกี่ยวกับผู้ใช้หรือสภาพแวดล้อมของผู้ใช้
                    PREFERENCE คือสิ่งที่ผู้ใช้ชอบ เลือกใช้ หรือมีความต้องการด้านรูปแบบ
                    GOAL คือสิ่งที่ผู้ใช้บอกว่าอยากทำ ต้องการทำ หรือกำลังมุ่งไปให้สำเร็จ
                    SKILL คือความสามารถหรือประสบการณ์ที่ผู้ใช้บอกว่ามี
                    PROJECT คือโครงการหรืองานที่ผู้ใช้กำลังทำหรือมีส่วนร่วม
                    หากข้อความมีหลายความหมาย ให้เก็บเฉพาะความหมายที่มีหลักฐานชัดที่สุด และอย่าฝืนสร้างหลาย memory
                    confidence ต้องสะท้อนความแข็งแรงของหลักฐานจริง ไม่ใช่ค่าคงที่: การยืนยันตรงและมีบริบทใช้ค่าสูงได้ ส่วนข้อความกำกวมให้ค่าต่ำหรือไม่เก็บ
                    reason ต้องอธิบายหลักฐานสั้น ๆ จากข้อความของ user เช่น "ผู้ใช้บอกว่าตนเองใช้ mac เป็นเครื่องหลัก"
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
