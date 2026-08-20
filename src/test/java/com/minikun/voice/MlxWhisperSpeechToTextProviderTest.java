package com.minikun.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.guardian.GuardianCommandResult;
import com.minikun.guardian.GuardianCommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MlxWhisperSpeechToTextProviderTest {
    @TempDir Path temporary;

    @Test
    void normalizesAudioThenRunsPinnedLocalAdapter() throws Exception {
        Path python = executable("python");
        Path script = Files.writeString(temporary.resolve("adapter.py"), "# adapter");
        Path model = Files.createDirectory(temporary.resolve("model"));
        Path afconvert = executable("afconvert");
        Path input = Files.write(temporary.resolve("input.m4a"), new byte[] {1, 2, 3});
        AtomicInteger calls = new AtomicInteger();
        List<List<String>> commands = new ArrayList<>();
        GuardianCommandRunner runner = (command, timeout) -> {
            commands.add(command);
            if (calls.getAndIncrement() == 0) {
                try { Files.write(Path.of(command.getLast()), new byte[] {1}); }
                catch (Exception exception) { throw new RuntimeException(exception); }
                return new GuardianCommandResult(0, false, "");
            }
            return new GuardianCommandResult(0, false,
                    "dependency notice\n{\"text\":\"สวัสดีครับ\",\"language\":\"th\",\"duration_seconds\":1.25}");
        };
        MlxWhisperSpeechToTextProvider provider = new MlxWhisperSpeechToTextProvider(
                runner, new ObjectMapper(), python, script, model, afconvert,
                "whisper-test", Duration.ofSeconds(1), Duration.ofSeconds(2));

        VoiceTranscription result = provider.transcribe(input, "th", "มินิคุง");

        assertEquals("สวัสดีครับ", result.text());
        assertEquals("th", result.language());
        assertEquals(2, commands.size());
        assertEquals(afconvert.toString(), commands.getFirst().getFirst());
        assertEquals(python.toString(), commands.getLast().getFirst());
        assertTrue(commands.getLast().contains(model.toString()));
        Path normalized = Path.of(commands.getFirst().getLast());
        assertFalse(Files.exists(normalized), "normalized temporary audio must be deleted");
    }

    private Path executable(String name) throws Exception {
        Path result = Files.writeString(temporary.resolve(name), "#!/bin/sh\n");
        result.toFile().setExecutable(true);
        return result;
    }
}
