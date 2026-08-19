package com.minikun.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Publishes server-side notifications to the configured ntfy topics. */
@Component
public final class NtfyNotificationService implements NotificationTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(NtfyNotificationService.class);

    private final HttpClient httpClient;
    private final boolean enabled;
    private final String token;
    private final String reminderTopic;
    private final String weatherTopic;

    public NtfyNotificationService(
            @Value("${minikun.ntfy.enabled:true}") boolean enabled,
            @Value("${minikun.ntfy.timeout:10s}") Duration timeout,
            @Value("${minikun.ntfy.token:}") String token,
            @Value("${minikun.ntfy.reminder-topic:}") String reminderTopic,
            @Value("${minikun.ntfy.weather-topic:}") String weatherTopic) {
        this.enabled = enabled;
        this.token = Objects.requireNonNullElse(token, "").trim();
        this.reminderTopic = requireTopic(reminderTopic, "reminder topic");
        this.weatherTopic = requireTopic(weatherTopic, "weather topic");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(timeout, "ntfy timeout must not be null"))
                .build();
    }

    @Override
    public void publish(NotificationChannel channel, String title, String message, int priority, String tags) {
        Objects.requireNonNull(channel, "notification channel must not be null");
        Objects.requireNonNull(title, "notification title must not be null");
        Objects.requireNonNull(message, "notification message must not be null");
        if (message.isBlank()) {
            throw new IllegalArgumentException("notification message must not be blank");
        }
        if (!enabled) {
            LOGGER.info("process=notification event=skipped channel={} reason=disabled", channel);
            return;
        }
        int safePriority = Math.max(1, Math.min(5, priority));
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(topic(channel)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/plain; charset=UTF-8")
                .header("Title", asciiHeader(title))
                .header("Priority", Integer.toString(safePriority))
                .header("Tags", Objects.requireNonNullElse(tags, ""))
                .POST(HttpRequest.BodyPublishers.ofString(message));
        if (!token.isBlank()) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("ntfy returned HTTP " + response.statusCode());
            }
            LOGGER.info("process=notification event=published channel={} status={} priority={}",
                    channel, response.statusCode(), safePriority);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ntfy notification was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("ntfy notification failed", exception);
        }
    }

    private String topic(NotificationChannel channel) {
        return channel == NotificationChannel.REMINDER ? reminderTopic : weatherTopic;
    }

    private String requireTopic(String value, String label) {
        String topic = Objects.requireNonNullElse(value, "").trim();
        if (topic.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        URI uri = URI.create(topic);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException(label + " must be an HTTP(S) URL");
        }
        return topic;
    }

    private String asciiHeader(String value) {
        return value.codePoints()
                .filter(codePoint -> codePoint >= 0x20 && codePoint <= 0x7E)
                .collect(StringBuilder::new,
                        (builder, codePoint) -> builder.appendCodePoint(codePoint),
                        StringBuilder::append)
                .toString()
                .trim();
    }
}
