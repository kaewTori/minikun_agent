package com.minikun.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** TTS bridge for the external Python VaniraTTS server. */
public final class PythonVaniraTextToSpeechProvider implements TextToSpeechProvider, AutoCloseable {
    private final HttpClient client;
    private final URI baseUri;
    private final ObjectMapper objectMapper;
    private final Path python;
    private final Path serverScript;
    private final Path workingDirectory;
    private final Path modelDirectory;
    private final Path logFile;
    private final String host;
    private final int port;
    private final boolean autoStart;
    private final Duration startupTimeout;
    private final Duration requestTimeout;
    private final Object lifecycleLock = new Object();
    private volatile Process managedProcess;
    private volatile boolean closed;

    public PythonVaniraTextToSpeechProvider(
            HttpClient client,
            URI baseUri,
            ObjectMapper objectMapper,
            Path python,
            Path serverScript,
            Path workingDirectory,
            Path modelDirectory,
            Path logFile,
            String host,
            int port,
            boolean autoStart,
            Duration startupTimeout,
            Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "TTS client must not be null");
        this.baseUri = Objects.requireNonNull(baseUri, "TTS base URI must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.python = absolute(python, "Python path");
        this.serverScript = absolute(serverScript, "VaniraTTS server script");
        this.workingDirectory = absolute(workingDirectory, "TTS working directory");
        this.modelDirectory = absolute(modelDirectory, "VaniraTTS model path");
        this.logFile = absolute(logFile, "TTS log path");
        this.host = Objects.requireNonNullElse(host, "127.0.0.1").trim();
        if (this.host.isBlank()) throw new IllegalArgumentException("TTS host must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("TTS port is invalid");
        this.port = port;
        this.autoStart = autoStart;
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("TTS startup timeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("TTS request timeout must be positive");
        }
        this.startupTimeout = startupTimeout;
        this.requestTimeout = requestTimeout;
    }

    @Override
    public VoiceAudio synthesize(String text, String systemVoice, String format, double speed) {
        String normalizedFormat = Objects.requireNonNullElse(format, "wav").trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (!"wav".equals(normalizedFormat)) {
            throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                    "VaniraTTS speech synthesis currently supports wav only");
        }
        ensureReady();
        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "text", Objects.requireNonNullElse(text, ""),
                    "speaker", speakerNumber(systemVoice),
                    "speed", speed,
                    "volume", 1.0));
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/tts"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2 || response.body().length == 0) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return new VoiceAudio(response.body(), "wav", "audio/wav");
        } catch (VoiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                    "VaniraTTS speech synthesis failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "VaniraTTS speech synthesis was interrupted");
        }
    }

    @Override
    public boolean available() {
        return configured() && (autoStart || healthy());
    }

    private boolean configured() {
        return Files.isExecutable(python)
                && Files.isRegularFile(serverScript)
                && Files.isDirectory(workingDirectory)
                && Files.isRegularFile(modelDirectory.resolve("tts.onnx"))
                && Files.isRegularFile(modelDirectory.resolve("vocab.json"));
    }

    private boolean healthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return false;
            JsonNode root = objectMapper.readTree(response.body());
            return "ok".equalsIgnoreCase(root.path("status").asText());
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void ensureReady() {
        if (healthy()) return;
        if (!autoStart) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                "VaniraTTS speech server is not running");
        synchronized (lifecycleLock) {
            if (healthy()) return;
            if (closed) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "VaniraTTS speech provider is closed");
            if (!configured()) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "VaniraTTS Python runtime or model is not installed");
            if (managedProcess == null || !managedProcess.isAlive()) startServer();
            long deadline = System.nanoTime() + startupTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (healthy()) return;
                if (managedProcess != null && !managedProcess.isAlive()) break;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                            "VaniraTTS speech server startup was interrupted");
                }
            }
        }
        stopServer();
        throw new VoiceException(VoiceErrorCode.TIMEOUT,
                "VaniraTTS speech server did not become ready before the startup timeout");
    }

    private void startServer() {
        try {
            Path parent = logFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    python.toString(), serverScript.toString(),
                    "--host", host, "--port", Integer.toString(port),
                    "--model-dir", modelDirectory.toString()));
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Map<String, String> environment = builder.environment();
            environment.put("PYTHONUNBUFFERED", "1");
            managedProcess = builder.start();
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "VaniraTTS Python server could not be started");
        }
    }

    private void stopServer() {
        Process process = managedProcess;
        managedProcess = null;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private int speakerNumber(String value) {
        String speaker = Objects.requireNonNullElse(value, "3").trim().toLowerCase(java.util.Locale.ROOT);
        if (speaker.isBlank() || "default".equals(speaker) || "male".equals(speaker)) return 3;
        if ("male2".equals(speaker)) return 4;
        if ("female".equals(speaker)) return 1;
        if ("female2".equals(speaker)) return 2;
        try {
            int number = Integer.parseInt(speaker);
            if (number >= 1 && number <= 6) return number;
        } catch (NumberFormatException ignored) {
            // Fall through to the same client-facing validation error.
        }
        throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                "VaniraTTS speaker must be 1..6 or a known voice alias");
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
            stopServer();
        }
    }

    private static Path absolute(Path value, String label) {
        Path path = Objects.requireNonNull(value, label + " must not be null").toAbsolutePath().normalize();
        if (!path.isAbsolute()) throw new IllegalArgumentException(label + " must be absolute");
        return path;
    }
}
