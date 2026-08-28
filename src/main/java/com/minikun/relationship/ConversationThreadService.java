package com.minikun.relationship;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Owns durable conversational open loops and explicit-consent check-ins. */
public final class ConversationThreadService {
    private static final Pattern ISO_DATE_TIME = Pattern.compile("\\b(20\\d{2}-\\d{2}-\\d{2})(?:[ T](\\d{1,2}:\\d{2}))?\\b");
    private static final Pattern THAI_TIME = Pattern.compile("(?:ตอน|เวลา)?\\s*(\\d{1,2})(?:[:.]([0-5]\\d))?\\s*(?:น\\.|นาฬิกา)?");
    private final ConversationThreadStore store;
    private final Clock clock;
    private final ZoneId zone;

    public ConversationThreadService(ConversationThreadStore store, Clock clock, ZoneId zone) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
    }

    public ConversationThread create(String ownerId, String conversationId, String topic, String summary,
            String lastDecision, String unresolvedQuestion, Instant checkInAt, boolean checkInConsent) {
        String owner = required(ownerId, "owner id");
        String conversation = required(conversationId, "conversation id");
        Instant now = clock.instant();
        String normalizedTopic = bounded(required(topic, "topic"), 240);
        Optional<ConversationThread> existing = store.findOpenByFingerprint(owner, fingerprint(normalizedTopic));
        ConversationThread value = existing.map(current -> new ConversationThread(current.id(), owner,
                conversation, normalizedTopic, prefer(summary, current.summary()),
                prefer(lastDecision, current.lastDecision()), prefer(unresolvedQuestion, current.unresolvedQuestion()),
                ConversationThreadStatus.OPEN, checkInAt != null ? checkInAt : current.checkInAt(),
                checkInConsent || current.checkInConsent(), current.lastCheckInAt(), current.createdAt(), now))
                .orElseGet(() -> new ConversationThread(UUID.randomUUID(), owner, conversation, normalizedTopic,
                        summary, lastDecision, unresolvedQuestion, ConversationThreadStatus.OPEN,
                        checkInAt, checkInConsent, null, now, now));
        return store.save(value);
    }

    /** Conservatively captures only turns that explicitly signal an unresolved topic or requested follow-up. */
    public Optional<ConversationThread> observeTurn(
            String ownerId, String conversationId, String userMessage, String assistantMessage) {
        String text = normalize(userMessage);
        boolean explicitCheckIn = contains(text, "ถามอีกที", "ตามเรื่องนี้", "กลับมาถาม", "เช็กอีกที",
                "เช็คอีกที", "follow up", "check in")
                || contains(text, "ถาม", "เช็ก", "เช็ค") && contains(text, "อีกที", "อีกครั้ง");
        boolean unresolved = contains(text, "ยังไม่รู้", "ยังตัดสินใจไม่ได้", "ลังเล", "ไว้ค่อย", "ค่อยคิด",
                "ขอคิดก่อน", "ยังไม่จบ", "ยังไม่ได้", "not sure yet", "haven't decided", "think about it");
        if (!explicitCheckIn && !unresolved) return Optional.empty();

        String topic = topic(userMessage);
        String question = unresolved ? bounded(userMessage, 800) : "";
        Instant checkInAt = explicitCheckIn ? parseCheckIn(text).orElse(null) : null;
        return Optional.of(create(ownerId, conversationId, topic, bounded(userMessage, 1200),
                decision(assistantMessage), question, checkInAt, explicitCheckIn));
    }

    public ConversationThread update(String ownerId, UUID id, String topic, String summary, String lastDecision,
            String unresolvedQuestion, ConversationThreadStatus status, Instant checkInAt,
            Boolean checkInConsent) {
        ConversationThread current = find(ownerId, id);
        Instant effectiveCheckIn = checkInAt;
        boolean consent = checkInConsent == null ? current.checkInConsent() : checkInConsent;
        if (!consent) effectiveCheckIn = null;
        else if (effectiveCheckIn == null) effectiveCheckIn = current.checkInAt();
        return store.save(new ConversationThread(current.id(), current.ownerId(), current.sourceConversationId(),
                prefer(topic, current.topic()), prefer(summary, current.summary()),
                prefer(lastDecision, current.lastDecision()), prefer(unresolvedQuestion, current.unresolvedQuestion()),
                status == null ? current.status() : status, effectiveCheckIn, consent, current.lastCheckInAt(),
                current.createdAt(), clock.instant()));
    }

    public ConversationThread resolve(String ownerId, UUID id) {
        return update(ownerId, id, null, null, null, null,
                ConversationThreadStatus.RESOLVED, null, false);
    }

    public ConversationThread markCheckedIn(ConversationThread current, Instant now) {
        return store.save(new ConversationThread(current.id(), current.ownerId(), current.sourceConversationId(),
                current.topic(), current.summary(), current.lastDecision(), current.unresolvedQuestion(),
                current.status(), current.checkInAt(), current.checkInConsent(), now,
                current.createdAt(), now));
    }

    public ConversationThread find(String ownerId, UUID id) {
        return store.find(required(ownerId, "owner id"), Objects.requireNonNull(id))
                .orElseThrow(() -> new IllegalArgumentException("conversation thread not found"));
    }

    public List<ConversationThread> list(String ownerId, ConversationThreadStatus status, int limit) {
        return store.list(required(ownerId, "owner id"), status, boundedLimit(limit));
    }

    public List<ConversationThread> due(Instant now) {
        return store.due(Objects.requireNonNull(now), 50);
    }

    /** Returns at most three relevant open loops as prompt-safe background. */
    public String promptContext(String ownerId, String conversationId, String latestMessage) {
        List<ConversationThread> open = store.list(required(ownerId, "owner id"), ConversationThreadStatus.OPEN, 50);
        if (open.isEmpty()) return "";
        Set<String> query = terms(latestMessage);
        List<ConversationThread> selected = open.stream()
                .sorted((left, right) -> Integer.compare(score(right, query, conversationId),
                        score(left, query, conversationId)))
                .filter(value -> score(value, query, conversationId) > 0 || query.isEmpty())
                .limit(3).toList();
        if (selected.isEmpty()) return "";
        StringBuilder result = new StringBuilder("Open conversational threads:\n");
        for (ConversationThread value : selected) {
            result.append("- ").append(value.topic());
            if (!value.lastDecision().isBlank()) result.append("; last decision: ").append(value.lastDecision());
            if (!value.unresolvedQuestion().isBlank()) result.append("; unresolved: ").append(value.unresolvedQuestion());
            if (value.checkInConsent() && value.checkInAt() != null) {
                result.append("; consented check-in: ").append(value.checkInAt());
            }
            result.append('\n');
        }
        result.append("Use these only when relevant. Do not pretend the user mentioned them in the current turn. "
                + "Do not schedule or send a check-in without explicit consent.");
        return result.toString();
    }

    Optional<Instant> parseCheckIn(String text) {
        Instant now = clock.instant();
        Matcher iso = ISO_DATE_TIME.matcher(text);
        if (iso.find()) {
            try {
                LocalDate date = LocalDate.parse(iso.group(1));
                LocalTime time = iso.group(2) == null ? LocalTime.of(9, 0) : LocalTime.parse(iso.group(2));
                return future(date.atTime(time).atZone(zone).toInstant(), now);
            } catch (DateTimeParseException ignored) { /* use conversational parser */ }
        }
        LocalDate date = null;
        LocalDate today = now.atZone(zone).toLocalDate();
        if (contains(text, "พรุ่งนี้", "tomorrow")) date = today.plusDays(1);
        else if (contains(text, "มะรืน", "day after tomorrow")) date = today.plusDays(2);
        else {
            DayOfWeek day = dayOfWeek(text);
            if (day != null) date = today.with(TemporalAdjusters.next(day));
        }
        if (date == null) return Optional.empty();
        LocalTime time = time(text).orElse(LocalTime.of(9, 0));
        return future(LocalDateTime.of(date, time).atZone(zone).toInstant(), now);
    }

    private Optional<Instant> future(Instant candidate, Instant now) {
        return candidate.isAfter(now) ? Optional.of(candidate) : Optional.empty();
    }

    private Optional<LocalTime> time(String text) {
        if (contains(text, "ตอนเช้า", "morning")) return Optional.of(LocalTime.of(9, 0));
        if (contains(text, "ตอนเที่ยง", "noon")) return Optional.of(LocalTime.NOON);
        if (contains(text, "ตอนบ่าย", "afternoon")) return Optional.of(LocalTime.of(14, 0));
        if (contains(text, "ตอนเย็น", "evening")) return Optional.of(LocalTime.of(18, 0));
        Matcher matcher = THAI_TIME.matcher(text);
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
            if (hour < 24) return Optional.of(LocalTime.of(hour, minute));
        }
        return Optional.empty();
    }

    private DayOfWeek dayOfWeek(String text) {
        if (contains(text, "วันจันทร์", "monday")) return DayOfWeek.MONDAY;
        if (contains(text, "วันอังคาร", "tuesday")) return DayOfWeek.TUESDAY;
        if (contains(text, "วันพุธ", "wednesday")) return DayOfWeek.WEDNESDAY;
        if (contains(text, "วันพฤหัส", "thursday")) return DayOfWeek.THURSDAY;
        if (contains(text, "วันศุกร์", "friday")) return DayOfWeek.FRIDAY;
        if (contains(text, "วันเสาร์", "saturday")) return DayOfWeek.SATURDAY;
        if (contains(text, "วันอาทิตย์", "sunday")) return DayOfWeek.SUNDAY;
        return null;
    }

    private int score(ConversationThread value, Set<String> query, String conversationId) {
        int score = value.sourceConversationId().equals(conversationId) ? 3 : 0;
        Set<String> threadTerms = terms(value.topic() + " " + value.summary());
        String threadText = normalize(value.topic() + " " + value.summary());
        for (String term : query) if (threadTerms.contains(term) || threadText.contains(term)) score += 2;
        if (value.checkInConsent() && value.checkInAt() != null) score++;
        return score;
    }

    private Set<String> terms(String value) {
        String normalized = normalize(value).replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ");
        Set<String> terms = new LinkedHashSet<>();
        for (String term : normalized.split(" ")) if (term.length() >= 3) terms.add(term);
        return terms;
    }

    private String topic(String message) {
        String normalized = Objects.requireNonNullElse(message, "").replaceAll("\\s+", " ").trim();
        return bounded(normalized, 160);
    }

    private String decision(String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) return "";
        String firstParagraph = assistantMessage.strip().split("\\n\\s*\\n", 2)[0];
        return bounded(firstParagraph, 600);
    }

    static String fingerprint(String topic) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalizeStatic(topic).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String normalizeStatic(String value) {
        return Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{M}\\p{N}]+", " ").trim();
    }

    private String normalize(String value) {
        return Objects.requireNonNullElse(value, "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
    private boolean contains(String value, String... values) {
        for (String candidate : values) if (value.contains(candidate)) return true;
        return false;
    }
    private int boundedLimit(int value) { return Math.max(1, Math.min(value <= 0 ? 50 : value, 200)); }
    private String required(String value, String label) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank() || "*".equals(normalized)) throw new IllegalArgumentException(label + " must not be blank");
        return normalized;
    }
    private String prefer(String proposed, String current) {
        return proposed == null || proposed.isBlank() ? Objects.requireNonNullElse(current, "") : proposed.trim();
    }
    private String bounded(String value, int maximum) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum).stripTrailing();
    }
}
