package com.minikun.ups;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-only NUT client. Never authenticates, sends control commands, or caches stale measurements. */
public final class NutUpsClient {
    private static final Pattern VARIABLE = Pattern.compile("VAR ([A-Za-z0-9_.-]+) ([A-Za-z0-9_.-]+) \"((?:[^\"\\\\]|\\\\.)*)\"");
    private final String host;
    private final int port;
    private final String name;
    private final int timeoutMillis;

    public NutUpsClient(String host, int port, String name, Duration timeout) {
        if (host == null || host.isBlank() || port < 1 || port > 65535
                || name == null || !name.matches("[A-Za-z0-9_.-]{1,128}")
                || timeout == null || timeout.toMillis() < 1 || timeout.toMillis() > 30_000) {
            throw new IllegalArgumentException("invalid NUT connection configuration");
        }
        this.host = host;
        this.port = port;
        this.name = name;
        this.timeoutMillis = (int) timeout.toMillis();
    }

    public Snapshot read() {
        Instant queriedAt = Instant.now();
        long deadline = System.nanoTime() + Duration.ofMillis(timeoutMillis).toNanos();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.getOutputStream().write(("LIST VAR " + name + "\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try (var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String query = "LIST VAR " + name;
                String first = line(reader, socket, deadline);
                if (first.startsWith("ERR ")) return unavailable(queriedAt, error(first));
                if (!first.equals("BEGIN " + query)) throw new IOException("invalid NUT response");
                Map<String, String> variables = new LinkedHashMap<>();
                for (int count = 0; count < 512; count++) {
                    String value = line(reader, socket, deadline);
                    if (value.startsWith("ERR ")) return unavailable(queriedAt, error(value));
                    if (value.equals("END " + query)) {
                        if (variables.getOrDefault("ups.status", "").isBlank()) throw new IOException("missing UPS status");
                        return new Snapshot(queriedAt, name, true, "", Map.copyOf(variables));
                    }
                    var match = VARIABLE.matcher(value);
                    if (!match.matches() || !match.group(1).equals(name)) throw new IOException("invalid NUT variable");
                    variables.put(match.group(2), unescape(match.group(3)));
                }
                throw new IOException("NUT response exceeds limit");
            }
        } catch (IOException exception) {
            return unavailable(queriedAt, "NUT_UNAVAILABLE");
        }
    }

    private String line(BufferedReader reader, Socket socket, long deadline) throws IOException {
        StringBuilder result = new StringBuilder();
        while (result.length() < 4096) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000;
            if (remaining < 1) throw new IOException("NUT query timed out");
            socket.setSoTimeout((int) Math.min(remaining, timeoutMillis));
            int value = reader.read();
            if (value < 0) throw new IOException("incomplete NUT response");
            if (value == '\n') return result.toString();
            if (value != '\r') result.append((char) value);
        }
        throw new IOException("NUT line exceeds limit");
    }

    private String unescape(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char next = value.charAt(index);
            if (next == '\\') next = value.charAt(++index);
            result.append(next);
        }
        return result.toString();
    }

    private String error(String response) {
        String code = response.substring(4);
        return code.matches("[A-Z0-9-]{1,64}") ? code : "NUT_ERROR";
    }

    private Snapshot unavailable(Instant queriedAt, String error) {
        return new Snapshot(queriedAt, name, false, error, Map.of());
    }

    /** queriedAt is the query time; measurements follow the driver's poll interval. */
    public record Snapshot(Instant queriedAt, String name, boolean available, String error,
            Map<String, String> variables) { }
}
