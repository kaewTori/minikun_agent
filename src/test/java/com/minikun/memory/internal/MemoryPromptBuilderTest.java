package com.minikun.memory.internal;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;

class MemoryPromptBuilderTest {
    private final MemoryPromptBuilder builder = new MemoryPromptBuilder(new ObjectMapper());

    @Test
    void buildsAnExplicitStrictExtractionPrompt() {
        String prompt = builder.build(
                new CompletedConversation("conversation-1", List.of(
                        new CompletedConversation.Message("user", "I prefer Vim."),
                        new CompletedConversation.Message("assistant", "Noted."))),
                Instant.parse("2026-08-02T00:00:00Z"));

        assertTrue(prompt.contains("หน้าที่เดียวของคุณคือค้นหาข้อเท็จจริงที่คงอยู่ระยะยาวและมีหลักฐานจากข้อความของ user เท่านั้น"));
        assertTrue(prompt.contains("ให้ส่งคืน JSON object เพียงหนึ่ง object เท่านั้น"));
        assertTrue(prompt.contains("ห้ามสรุปจนรายละเอียดหาย"));
        assertTrue(prompt.contains("คำถามไม่ใช่ memory"));
        assertTrue(prompt.contains("โปรเจคที่เราทำอยู่คือโปรเจคอะไรหรอ?"));
        assertTrue(prompt.contains("คำตอบสั้นหรือคำเดี่ยว เช่น \"mac\""));
        assertTrue(prompt.contains("confidence ต้องสะท้อนความแข็งแรงของหลักฐานจริง ไม่ใช่ค่าคงที่"));
        assertFalse(prompt.contains("confidence เป็น 0.9 และ reason เป็น \"ผู้ใช้ระบุโดยตรง\""));
        assertTrue(prompt.contains("เลือก category ตามความหมายของข้อความ"));
        assertTrue(prompt.contains("PROFILE คือข้อมูลเกี่ยวกับผู้ใช้หรือสภาพแวดล้อมของผู้ใช้"));
        assertTrue(prompt.contains("{\"memories\":[]}"));
        assertTrue(prompt.contains("BEGIN USER MESSAGES"));
        assertTrue(prompt.contains("\"content\":\"I prefer Vim.\""));
        assertFalse(prompt.contains("Noted."));
        assertTrue(prompt.contains("Prompt version: " + MemoryPromptBuilder.VERSION));
    }
}
