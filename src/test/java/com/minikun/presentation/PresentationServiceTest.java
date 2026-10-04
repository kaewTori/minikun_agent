package com.minikun.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.ToolCallContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresentationServiceTest {
    @TempDir Path directory;

    @Test
    void ignoresNonRenderingArtDirectionMetadata() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);

        var created = service.create(new ToolCallContext(new ConversationId("conversation"), "call", "owner"), """
                {"title":"PostgreSQL Performance Tuning","language":"th","theme":"ocean",
                 "audience":"DBAs","visualMetaphor":"query plan as a map",
                 "slides":[{"title":"วัดก่อนปรับ","layout":"cover","body":"เริ่มจากหลักฐาน",
                 "artDirection":"clean editorial"}]}
                """);

        assertEquals("PostgreSQL Performance Tuning", created.presentation().title());
        assertEquals(1, created.presentation().slideCount());
        assertTrue(store.bytes(created.presentation()).length > 0);
    }

    @Test
    void createsFromNestedStructuredSpecWithoutSerializingItIntoAString() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);
        Map<String, Object> spec = Map.of(
                "title", "PostgreSQL Performance Tuning",
                "language", "th",
                "theme", "ocean",
                "audience", "DBAs",
                "slides", List.of(Map.of(
                        "title", "วัดก่อนปรับ",
                        "layout", "cover",
                        "body", "เริ่มจากหลักฐาน",
                        "artDirection", "clean editorial")));

        var created = service.create(new ToolCallContext(new ConversationId("conversation"), "call", "owner"), spec);

        assertEquals("PostgreSQL Performance Tuning", created.presentation().title());
        assertEquals(1, created.presentation().slideCount());
        assertTrue(store.bytes(created.presentation()).length > 0);
    }

    @Test
    void derivesEmptyPresentationAndSlideTitlesFromTheSlideBodyUsingAPlainObjectMapper() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);
        Map<String, Object> spec = Map.of(
                "title", "",
                "theme", "ocean",
                "slides", List.of(Map.of(
                        "title", "",
                        "layout", "editorial",
                        "body", "เริ่มจากการวัด query latency ก่อนปรับระบบ")));

        var created = service.create(new ToolCallContext(new ConversationId("conversation"), "call", "owner"), spec);
        var saved = mapper.readTree(created.presentation().specJson()).path("slides").get(0);

        assertEquals("เริ่มจากการวัด query latency ก่อนปรับระบบ", created.presentation().title());
        assertEquals("เริ่มจากการวัด query latency ก่อนปรับระบบ", saved.path("title").asText());
        assertTrue(store.bytes(created.presentation()).length > 0);
    }

    @Test
    void usesPaperThemeWhenTheModelReturnsAnUnsupportedTheme() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);
        Map<String, Object> spec = Map.of(
                "title", "PostgreSQL Performance Tuning",
                "theme", "minimalist",
                "slides", List.of(Map.of("title", "เริ่มจากข้อมูล", "layout", "cover")));

        var created = service.create(new ToolCallContext(new ConversationId("conversation"), "call", "owner"), spec);

        assertEquals("paper", created.presentation().theme());
        assertTrue(store.bytes(created.presentation()).length > 0);
    }

    @Test
    void pointsToTheFieldWhenStructuredSpecHasTheWrongValueType() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);

        var error = assertThrows(IllegalArgumentException.class, () -> service.create(
                new ToolCallContext(new ConversationId("conversation"), "call", "owner"),
                Map.of("title", Map.of("text", "Wrong type"), "theme", "ocean",
                        "slides", List.of(Map.of("title", "One", "layout", "cover")))));

        assertTrue(error.getMessage().contains("title"));
    }

    @Test
    void keepsRenderingWhenASpecialLayoutIsMissingItsOptionalContent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);
        Map<String, Object> spec = Map.of(
                "title", "PostgreSQL Performance Tuning",
                "theme", "ocean",
                "slides", List.of(
                        Map.of("title", "เปรียบเทียบ", "layout", "comparison"),
                        Map.of("title", "ไม่มีตัวเลข", "layout", "stat", "body", "เริ่มจากการวัด"),
                        Map.of("title", "ไม่มีคำอ้าง", "layout", "quote", "body", "ยึดข้อมูลจริง"),
                        Map.of("title", "ไม่มีลำดับ", "layout", "timeline", "body", "ใช้แผนที่ทำได้จริง")));

        var created = service.create(new ToolCallContext(new ConversationId("conversation"), "call", "owner"), spec);
        var saved = mapper.readTree(created.presentation().specJson()).path("slides");

        assertEquals(4, created.presentation().slideCount());
        assertEquals("ตัวเลือก A", saved.get(0).path("leftTitle").asText());
        assertEquals("ตัวเลือก B", saved.get(0).path("rightTitle").asText());
        assertEquals("editorial", saved.get(1).path("layout").asText());
        assertEquals("editorial", saved.get(2).path("layout").asText());
        assertEquals("editorial", saved.get(3).path("layout").asText());
    }

    @Test
    void reportsInvalidJsonAsAnActionableSpecError() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PresentationStore store = new PresentationStore(directory, 1_000_000, Duration.ofDays(1), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);

        var error = assertThrows(IllegalArgumentException.class, () -> service.create(
                new ToolCallContext(new ConversationId("conversation"), "call", "owner"),
                "```json { not valid } ```"));

        assertTrue(error.getMessage().startsWith("spec_json is invalid:"));
    }
}
