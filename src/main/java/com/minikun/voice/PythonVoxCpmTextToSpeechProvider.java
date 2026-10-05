package com.minikun.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** TTS bridge for the external Python VoxCPM/SiangTTS server. */
public final class PythonVoxCpmTextToSpeechProvider implements TextToSpeechProvider, AutoCloseable {
    private final HttpClient client;
    private final URI baseUri;
    private final ObjectMapper objectMapper;
    private final Path python;
    private final Path workingDirectory;
    private final Path baseModel;
    private final Path adapter;
    private final Path hfHome;
    private final Path logFile;
    private final String host;
    private final int port;
    private final boolean autoStart;
    private final String device;
    private final Duration startupTimeout;
    private final Duration requestTimeout;
    private final double cfgValue;
    private final int timesteps;
    private final Object lifecycleLock = new Object();
    private volatile Process managedProcess;
    private volatile boolean closed;
    private volatile boolean suspended;

    public PythonVoxCpmTextToSpeechProvider(
            HttpClient client,
            URI baseUri,
            ObjectMapper objectMapper,
            Path python,
            Path workingDirectory,
            Path baseModel,
            Path adapter,
            Path hfHome,
            Path logFile,
            String host,
            int port,
            boolean autoStart,
            String device,
            Duration startupTimeout,
            Duration requestTimeout,
            double cfgValue,
            int timesteps) {
        this.client = Objects.requireNonNull(client, "TTS client must not be null");
        this.baseUri = Objects.requireNonNull(baseUri, "TTS base URI must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.python = absolute(python, "Python path");
        this.workingDirectory = absolute(workingDirectory, "TTS working directory");
        this.baseModel = absolute(baseModel, "VoxCPM base model path");
        this.adapter = adapter == null ? null : absolute(adapter, "SiangTTS adapter path");
        this.hfHome = hfHome == null ? null : absolute(hfHome, "Hugging Face cache path");
        this.logFile = absolute(logFile, "TTS log path");
        this.host = Objects.requireNonNullElse(host, "127.0.0.1").trim();
        if (this.host.isBlank()) throw new IllegalArgumentException("TTS host must not be blank");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("TTS port is invalid");
        this.port = port;
        this.autoStart = autoStart;
        this.device = Objects.requireNonNullElse(device, "auto").trim();
        if (this.device.isBlank()) throw new IllegalArgumentException("TTS device must not be blank");
        if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
            throw new IllegalArgumentException("TTS startup timeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("TTS request timeout must be positive");
        }
        if (!Double.isFinite(cfgValue) || cfgValue <= 0) {
            throw new IllegalArgumentException("TTS cfg value must be positive");
        }
        if (timesteps < 4 || timesteps > 30) throw new IllegalArgumentException("TTS timesteps must be 4..30");
        this.startupTimeout = startupTimeout;
        this.requestTimeout = requestTimeout;
        this.cfgValue = cfgValue;
        this.timesteps = timesteps;
    }

    @Override
    public VoiceAudio synthesize(String text, String systemVoice, String format, double speed) {
        String normalizedFormat = Objects.requireNonNullElse(format, "wav").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"wav".equals(normalizedFormat)) {
            throw new VoiceException(VoiceErrorCode.INVALID_REQUEST,
                    "VoxCPM speech synthesis currently supports wav only");
        }
        ensureReady();
        try {
            String speaker = Objects.requireNonNullElse(systemVoice, "").trim();
            String boundary = "MinikunBoundary" + System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder(endpoint(speaker))
                    .timeout(requestTimeout)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, text)))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode());
            return new VoiceAudio(response.body(), "wav", "audio/wav");
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.PROCESSING_FAILED,
                    "VoxCPM speech synthesis failed");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "VoxCPM speech synthesis was interrupted");
        }
    }

    @Override
    public boolean available() {
        return !closed && !suspended && configured() && (autoStart || healthy());
    }

    private boolean configured() {
        return Files.isExecutable(python)
                && Files.isDirectory(workingDirectory)
                && Files.isDirectory(baseModel)
                && (adapter == null || Files.isDirectory(adapter));
    }

    private boolean healthy() {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
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
                    "VoxCPM speech provider is closed or disabled");
            if (healthy()) return;
            if (!autoStart) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "local TTS server is not running");
            if (!configured()) throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "VoxCPM Python runtime or model is not installed");
            if (managedProcess == null || !managedProcess.isAlive()) startServer();
            long deadline = System.nanoTime() + startupTimeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (healthy()) return;
                if (managedProcess != null && !managedProcess.isAlive()) break;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                            "VoxCPM speech server startup was interrupted");
                }
            }
            stopServer();
            throw new VoiceException(VoiceErrorCode.TIMEOUT,
                    "VoxCPM speech server did not become ready before the startup timeout");
        }
    }

    private URI endpoint(String speaker) {
        if (speaker.isBlank() || "default".equalsIgnoreCase(speaker)) return baseUri.resolve("/tts");
        return baseUri.resolve("/tts/speaker/" + URLEncoder.encode(speaker, StandardCharsets.UTF_8));
    }

    private byte[] multipart(String boundary, String text) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        formPart(body, boundary, "text", text);
        formPart(body, boundary, "cfg_value", Double.toString(cfgValue));
        formPart(body, boundary, "timesteps", Integer.toString(timesteps));
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private static void formPart(ByteArrayOutputStream body, String boundary, String name, String value) {
        body.writeBytes(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.writeBytes(Objects.requireNonNullElse(value, "").getBytes(StandardCharsets.UTF_8));
        body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private void startServer() {
        try {
            Path parent = logFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            ProcessBuilder builder = new ProcessBuilder(List.of(
                    python.toString(), "-m", "uvicorn", "src.serve:app",
                    "--host", host, "--port", Integer.toString(port)));
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            Map<String, String> environment = builder.environment();
            environment.put("SIANGTTS_BASE_MODEL", baseModel.toString());
            environment.put("SIANGTTS_ADAPTER", adapter == null ? "" : adapter.toString());
            environment.put("SIANGTTS_DEVICE", device);
            environment.put("PYTORCH_ENABLE_MPS_FALLBACK", "1");
            if (hfHome != null) environment.put("HF_HOME", hfHome.toString());
            managedProcess = builder.start();
        } catch (IOException exception) {
            throw new VoiceException(VoiceErrorCode.UNAVAILABLE,
                    "VoxCPM Python server could not be started");
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
