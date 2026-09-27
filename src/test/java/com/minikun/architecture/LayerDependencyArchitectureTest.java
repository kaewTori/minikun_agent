package com.minikun.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class LayerDependencyArchitectureTest {
    private static final Path MAIN_SOURCE = Path.of("src/main/java/com/minikun");

    @Test
    void modelLayerDoesNotDependOnHttpApiAdapters() throws Exception {
        try (var sources = Files.walk(MAIN_SOURCE.resolve("model"))) {
            List<Path> violations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> contains(path, "com.minikun.agent.minikun_agent.api"))
                    .toList();

            assertTrue(violations.isEmpty(), "model -> API dependency violations: " + violations);
        }
    }

    @Test
    void chatFacadeKeepsExtractedInfrastructureBehindDedicatedBoundaries() throws Exception {
        Path chatService = MAIN_SOURCE.resolve(
                "agent/minikun_agent/api/openai/ChatService.java");
        String source = Files.readString(chatService);

        assertTrue(source.lines().count() <= 1_100,
                "ChatService exceeded its refactored size budget");
        for (String forbidden : List.of(
                "new SearchRequest(",
                "new CompletedConversation(",
                "OllamaChatOptions",
                "ObjectMapper")) {
            assertFalse(source.contains(forbidden),
                    "ChatService contains extracted responsibility: " + forbidden);
        }
        for (String boundary : List.of(
                "ChatKnowledgeResolver",
                "ChatPromptFactory",
                "ChatModelGateway",
                "ChatTurnFinalizer",
                "OpenAiChatResponseFactory")) {
            assertTrue(source.contains(boundary), "missing chat boundary: " + boundary);
        }
    }

    private boolean contains(Path path, String text) {
        try {
            return Files.readString(path).contains(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
