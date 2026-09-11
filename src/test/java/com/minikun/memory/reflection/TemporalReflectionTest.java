package com.minikun.memory.reflection;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.memory.model.CompletedConversation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TemporalReflectionTest {
    @Test
    void groundsEvidenceInUserAndAssignsObservationTimeOnServer() {
        var time = Instant.parse("2026-09-10T00:00:00Z");
        var input = new CompletedConversation("owner", "chat", List.of(
            new CompletedConversation.Message("user", "เมื่อก่อนชอบหวาน ตอนนี้ไม่แล้ว"),
            new CompletedConversation.Message("assistant", "คุณชอบขม")), time);
        String json = """
            {"memories":[{"category":"PREFERENCE","content":"ไม่ชอบหวานแล้ว","confidence":0.95,
            "reason":"ผู้ใช้เปลี่ยนความชอบ","fact":{"subject":"user","key":"food.sweet","value":"dislikes",
            "validFrom":null,"validTo":null,"evidence":"ตอนนี้ไม่แล้ว"}}]}
            """;
        var parser = new ReflectionParser(new ObjectMapper());
        assertEquals(time, parser.parse(json, input).getFirst().fact().recordedAt());
        assertThrows(RuntimeException.class, () -> parser.parse(json.replace("ตอนนี้ไม่แล้ว", "คุณชอบขม"), input));
        assertThrows(RuntimeException.class, () -> parser.parse(json.replace("\"validTo\":null", "\"validTo\":\"bad-date\""), input));
        assertThrows(RuntimeException.class, () -> parser.parse(json.replace("\"subject\":\"user\"", "\"subject\":\"\""), input));
    }
}
