package com.minikun.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** TTS bridge for the external Python mixed-language TTS server. */
public final class PythonVaniraTextToSpeechProvider implements TextToSpeechProvider, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PythonVaniraTextToSpeechProvider.class);
    private final HttpClient client;
    private final URI baseUri;
    private final ObjectMapper objectMapper;
    private final Path python;
    private final Path serverScript;
    private final Path workingDirectory;
    private final Path modelDirectory;
    private final Path kokoroModelDirectory;
    private final Path kokoroVoiceFile;
    private final Path logFile;
    private final String host;
    private final int port;
    private final boolean autoStart;
    private final boolean warmupEnabled;
    private final Duration startupTimeout;
    private final Duration requestTimeout;
    private final Object lifecycleLock = new Object();
    private volatile Process managedProcess;
    private volatile boolean closed;
    private volatile boolean suspended;

    public PythonVaniraTextToSpeechProvider(
            HttpClient client,
            URI baseUri,
            ObjectMapper objectMapper,
            Path python,
            Path serverScript,
            Path workingDirectory,
            Path modelDirectory,
            Path kokoroModelDirectory,
            Path kokoroVoiceFile,
            Path logFile,
            String host,
            int port,
            boolean autoStart,
            boolean warmupEnabled,
            Duration startupTimeout,
            Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "TTS client must not be null");
        this.baseUri = Objects.requireNonNull(baseUri, "TTS base URI must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.python = absolute(python, "Python path");
        this.serverScript = absolute(serverScript, "VaniraTTS server script");
        this.workingDirectory = absolute(workingDirectory, "TTS working directory");
        this.modelDirectory = absolute(modelDirectory, "VaniraTTS model path");
        this.kokoroModelDirectory = absolute(kokoroModelDirectory, "Kokoro model path");
        this.kokoroVoiceFile = absolute(kokoroVoiceFile, "Kokoro voice path");
        this.logFile = absolute(logFile, "TTS log path");
        this.host = Objects.requireNonNullElse(host, "127.0.0.1").trim();
        if (this.host.isBlank()) throw new IllegalArgumentException("TTS host must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("TTS port is invalid");
        this.port = port;
        this.autoStart = autoStart;
        this.warmupEnabled = warmupEnabled;
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
                    "local speech synthesis currently supports wav only");
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
                    "local speech synthesis failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "local speech synthesis was interrupted");
        }
    }

    @Override
    public boolean available() {
        return !closed && !suspended && configured() && (autoStart || healthy());
    }

    /** Starts the local server and warms both language paths without delaying app startup. */
    public void warmup() {
        if (!warmupEnabled || !autoStart || !configured()) return;
        Thread thread = new Thread(() -> {
            try {
                synthesize("Mini-kun is ready to speak พร้อมแล้วครับ", "3", "wav", 1.0);
                LOG.info("process=voice_warmup event=completed engines=vaniratts,kokoro");
            } catch (VoiceException exception) {
                LOG.warn("process=voice_warmup event=failed reason={}", exception.getMessage());
            }
        }, "minikun-voice-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean configured() {
        return Files.isExecutable(python)
                && Files.isRegularFile(serverScript)
                && Files.isDirectory(workingDirectory)
                && Files.isRegularFile(modelDirectory.resolve("tts.onnx"))
                && Files.isRegularFile(modelDirectory.resolve("vocab.json"))
                && Files.isRegularFile(kokoroModelDirectory.resolve("config.json"))
                && Files.isRegularFile(kokoroModelDirectory.resolve("kokoro-v1_0.pth"))
                && Files.isRegularFile(kokoroVoiceFile);
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
        synchronized (lifecycleLock) {
            if (closed || suspended) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "local speech provider is closed or disabled");
            if (healthy()) return;
            if (!autoStart) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "local TTS server is not running");
            if (!configured()) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "local TTS Python runtime or model is not installed");
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
                            "local speech server startup was interrupted");
                }
            }
            stopServer();
            throw new VoiceException(VoiceErrorCode.TIMEOUT,
                    "local speech server did not become ready before the startup timeout");
        }
    }

    private void startServer() {
        try {
            Path parent = logFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    python.toString(), serverScript.toString(),
                    "--host", host, "--port", Integer.toString(port),
                    "--model-dir", modelDirectory.toString(),
                    "--kokoro-model-dir", kokoroModelDirectory.toString(),
                    "--kokoro-voice", kokoroVoiceFile.toString()));
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Map<String, String> environment = builder.environment();
            environment.put("PYTHONUNBUFFERED", "1");
            Path pythonBin = python.getParent();
            if (pythonBin != null && pythonBin.getParent() != null) {
                environment.put("VIRTUAL_ENV", pythonBin.getParent().toString());
                environment.put("PATH", pythonBin + File.pathSeparator
                        + environment.getOrDefault("PATH", ""));
            }
            managedProcess = builder.start();
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "local TTS Python server could not be started");
        }
    }

    @Override
    public boolean running() { return healthy(); }

    @Override
    public void releaseRuntime() {
        synchronized (lifecycleLock) {
            if ((managedProcess == null || !managedProcess.isAlive()) && healthy()) {
                throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                        "TTS server is externally managed; stop it through its owner");
            }
            stopServer();
            suspended = true;
        }
    }

    @Override
    public void resumeRuntime() {
        synchronized (lifecycleLock) {
            if (closed) throw new VoiceException(VoiceErrorCode.UNAVAILABLE, "TTS provider is closed");
            suspended = false;
        }
    }

    private void stopServer() {
        Process process = managedProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        throw new VoiceException(VoiceErrorCode.UNAVAILABLE, "TTS server did not stop");
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new VoiceException(VoiceErrorCode.UNAVAILABLE, "TTS shutdown was interrupted");
            }
        }
        managedProcess = null;
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
