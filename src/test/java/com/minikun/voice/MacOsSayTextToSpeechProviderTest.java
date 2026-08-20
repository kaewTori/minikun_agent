package com.minikun.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.minikun.guardian.GuardianCommandResult;
import com.minikun.guardian.GuardianCommandRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MacOsSayTextToSpeechProviderTest {
    @TempDir Path temporary;

    @Test
    void usesAllowlistedArgvAndDeletesTemporaryOutput() throws Exception {
        Path say = Files.writeString(temporary.resolve("say"), "#!/bin/sh\n");
        say.toFile().setExecutable(true);
        AtomicReference<Path> output = new AtomicReference<>();
        AtomicReference<Path> input = new AtomicReference<>();
        GuardianCommandRunner runner = (command, timeout) -> {
            int outputIndex = command.indexOf("-o") + 1;
            output.set(Path.of(command.get(outputIndex)));
            input.set(Path.of(command.get(command.indexOf("-f") + 1)));
            try { Files.write(output.get(), new byte[2048]); }
            catch (Exception exception) { throw new RuntimeException(exception); }
            assertEquals("Kanya", command.get(command.indexOf("-v") + 1));
            assertEquals("190", command.get(command.indexOf("-r") + 1));
            return new GuardianCommandResult(0, false, "");
        };
        MacOsSayTextToSpeechProvider provider = new MacOsSayTextToSpeechProvider(
                runner, say, Duration.ofSeconds(2), 4096);

        VoiceAudio result = provider.synthesize("สวัสดี", "Kanya", "wav", 1.0);

        assertEquals("audio/wav", result.mediaType());
        assertEquals(2048, result.data().length);
        assertFalse(Files.exists(input.get()), "speech input temporary text must be deleted");
        assertFalse(Files.exists(output.get()), "synthesized temporary audio must be deleted");
    }
}
