package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.GenerationOptions;
import com.minikun.model.PeerStatus;

class ChatGptReasoningClientTest {
    @TempDir Path directory;

    @Test
    void selectsAvailableLunaMaxAndDeletesThreadAfterAnswer() throws Exception {
        Path command = directory.resolve("fake-codex");
        Files.writeString(command, """
                #!/bin/sh
                while IFS= read -r line; do
                  echo "$line" >> "$0.log"
                  case "$line" in
                    *'"method":"initialize"'*) echo '{"id":0,"result":{}}' ;;
                    *'"method":"model/list"'*) echo '{"id":1,"result":{"data":[{"model":"gpt-6-astra","isDefault":true},{"model":"gpt-5.6-luna","isDefault":false}]}}' ;;
                    *'"method":"thread/start"'*) echo '{"id":2,"result":{"thread":{"id":"test-thread"}}}' ;;
                    *'"method":"turn/start"'*) echo '{"method":"item/completed","params":{"item":{"type":"agentMessage","text":"handoff"}}}'; echo '{"method":"turn/completed","params":{"turn":{"status":"completed"}}}' ;;
                    *'"method":"thread/delete"'*) echo '{"id":4,"result":{}}' ;;
                  esac
                done
                """);
        command.toFile().setExecutable(true);

        ChatGptReasoningClient client = new ChatGptReasoningClient(
                new ObjectMapper(), command.toString(), "gpt-6-luna", Duration.ofSeconds(5), true,
                directory.toString());
        var result = client.consult("prompt", GenerationOptions.Reasoning.MEDIUM);
        String requests = Files.readString(Path.of(command + ".log"));

        assertEquals(PeerStatus.COMPLETE, result.status());
        assertEquals("handoff", result.content());
        assertTrue(requests.contains("\"method\":\"thread/start\",\"id\":2,\"params\":{\"cwd\""));
        assertTrue(requests.contains("\"model\":\"gpt-5.6-luna\""));
        assertFalse(requests.contains("\"model\":\"gpt-6-luna\""));
        assertTrue(requests.contains("\"effort\":\"max\""));
        assertTrue(requests.contains("\"method\":\"thread/delete\""));
        assertTrue(requests.contains("\"threadId\":\"test-thread\""));
    }
}
