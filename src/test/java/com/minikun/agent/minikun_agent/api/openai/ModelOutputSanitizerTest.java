package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelOutputSanitizerTest {
    @Test
    void removesAgenticProtocolFromBlockingOutput() {
        assertEquals("คำตอบก่อนหลุด", ModelOutputSanitizer.clean(
                "คำตอบก่อนหลุด<|tool*call>call:GoogleSearch{queries:[...]}"));
    }

    @Test
    void stopsStreamingAfterProtocolBegins() {
        ModelOutputSanitizer.Stream sanitizer = new ModelOutputSanitizer.Stream();

        assertEquals("คำตอบ", sanitizer.accept("คำตอบ"));
        assertEquals("", sanitizer.accept("<|tool*call>call:GoogleSearch"));
        assertEquals("", sanitizer.accept("ข้อความที่ไม่ควรหลุด"));
    }
}
