package com.minikun.presentation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.ToolCallContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class PresentationControllerTest {
    @TempDir Path root;

    @Test
    void downloadsTheOwnerScopedDeckAndKeepsRevisionsSeparate() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PresentationStore store = new PresentationStore(root, 1024 * 1024, Duration.ofDays(30), mapper,
                Clock.systemUTC());
        PresentationService service = new PresentationService(mapper, null, store);
        PresentationController controller = new PresentationController(service, "secret");
        ToolCallContext firstTurn = new ToolCallContext(new ConversationId("chat-1"), "create", "alice", "req-1");

        var first = service.create(firstTurn, deckJson("ต้นฉบับ")).presentation();
        ToolCallContext secondTurn = new ToolCallContext(new ConversationId("chat-1"), "revise", "alice", "req-2");
        var revision = service.revise(secondTurn, first.artifactId(), deckJson("ฉบับแก้ไข")).presentation();

        var downloaded = controller.download(first.artifactId(), "alice", "secret");
        assertEquals(MediaType.parseMediaType(PresentationStore.CONTENT_TYPE), downloaded.getHeaders().getContentType());
        assertEquals(first.bytes(), downloaded.getHeaders().getContentLength());
        assertArrayEquals(service.bytes(first), downloaded.getBody());
        assertEquals(first.artifactId(), revision.parentArtifactId());
        assertEquals("ต้นฉบับ", store.read(first.artifactId(), "alice").title());
        assertEquals("ฉบับแก้ไข", store.latest("alice", "chat-1").title());
        ResponseStatusException wrongOwner = assertThrows(ResponseStatusException.class,
                () -> controller.download(first.artifactId(), "bob", "secret"));
        assertEquals(HttpStatus.NOT_FOUND, wrongOwner.getStatusCode());
        ResponseStatusException badToken = assertThrows(ResponseStatusException.class,
                () -> controller.download(first.artifactId(), "alice", "wrong"));
        assertEquals(HttpStatus.FORBIDDEN, badToken.getStatusCode());
    }

    private String deckJson(String title) {
        return "{\"title\":\"" + title + "\",\"language\":\"th\",\"theme\":\"paper\","
                + "\"slides\":[{\"title\":\"" + title + "\",\"layout\":\"cover\",\"body\":\"สรุป\"}]}";
    }
}
