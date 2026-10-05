package com.minikun.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Private stdio connection to a dedicated headed browser profile on the host. */
public final class BrowserSessionClient implements AutoCloseable {
    private final ObjectMapper mapper;
    private final Path python;
    private final Path script;
    private final Path profile;
    private final Duration timeout;
    private final Set<String> hosts = new HashSet<>();
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    public BrowserSessionClient(ObjectMapper mapper, Path python, Path script, Path profile, Duration timeout) {
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("browser session timeout must be within 60 seconds");
        }
        this.mapper = mapper;
        this.python = python;
        this.script = script;
        this.profile = profile;
        this.timeout = timeout;
    }

    public boolean available() { return Files.isExecutable(python) && Files.isRegularFile(script); }

    public synchronized boolean handles(String url) {
        return process != null && process.isAlive() && hosts.contains(host(url));
    }

    public synchronized List<String> activeHosts() {
        return process != null && process.isAlive() ? hosts.stream().sorted().toList() : List.of();
    }

    public synchronized void open(String url) {
        JsonNode response = exchange("open", url);
        hosts.add(host(url));
        if (!response.path("url").asText().isBlank()) hosts.add(host(response.path("url").asText()));
    }

    public synchronized BrowserContent render(String url) {
        if (!handles(url)) throw new BrowserContentException("Open the website in Minikun browser session first");
        JsonNode response = exchange("render", url);
        String content = response.path("content").asText("");
        BrowserContent result = new BrowserContent(response.path("url").asText(url), content, "text/plain", false);
        int status = response.path("status_code").asInt(200);
        if (status >= 400 && new BrowserContentQualityClassifier().classify(result) != BrowserContentQuality.CHALLENGE_REQUIRED) {
            throw new BrowserContentException("website returned HTTP " + status);
        }
        return result;
    }

    private JsonNode exchange(String action, String url) {
        return exchange(action, url, Map.of());
    }

    public synchronized JsonNode control(String action, String url, Map<String, Object> arguments) {
        if (!handles(url)) throw new BrowserContentException("Open this website in the owner browser session first");
        JsonNode response = exchange(action, url, arguments);
        if (!response.path("url").asText().isBlank()) hosts.add(host(response.path("url").asText()));
        return response;
    }

    private JsonNode exchange(String action, String url, Map<String, Object> arguments) {
        if (!available()) throw new BrowserContentException("Browser session runtime is not installed");
        try {
            if (process == null || !process.isAlive()) {
                hosts.clear();
                ProcessBuilder launch = new ProcessBuilder(python.toString(), script.toString(), profile.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                launch.environment().keySet().removeIf(key -> !Set.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL",
                        "DISPLAY", "XDG_RUNTIME_DIR").contains(key));
                process = launch.start();
                writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            }
            Map<String, Object> command = new java.util.LinkedHashMap<>(arguments);
            command.put("action", action);
            command.put("url", url);
            writer.write(mapper.writeValueAsString(command));
            writer.newLine();
            writer.flush();
            BufferedReader currentReader = reader;
            CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
                try { return currentReader.readLine(); }
                catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            });
            String line = result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (line == null) throw new BrowserContentException("Browser session stopped");
            JsonNode response = mapper.readTree(line);
            if (!response.path("success").asBoolean()) {
                throw new BrowserContentException(response.path("error").asText("Browser operation failed"));
            }
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            close();
            throw new BrowserContentException("Browser session interrupted", exception);
        } catch (BrowserContentException exception) {
            throw exception;
        } catch (Exception exception) {
            close();
            throw new BrowserContentException("Browser session unavailable or timed out", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) { }
        }
        if (process != null && process.isAlive()) {
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroyForcibly();
            }
        }
        hosts.clear();
        process = null;
    }

    private String host(String url) { return URI.create(url).getHost().toLowerCase(java.util.Locale.ROOT); }
}
