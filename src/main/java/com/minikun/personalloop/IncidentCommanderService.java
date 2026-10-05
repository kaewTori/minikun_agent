package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;

import com.minikun.guardian.GuardianFinding;
import com.minikun.guardian.GuardianReport;
import com.minikun.guardian.GuardianSeverity;
import com.minikun.guardian.HomelabGuardianService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Correlates sanitized Guardian evidence into durable incidents and an auditable timeline. */
public final class IncidentCommanderService {
    private final HomelabGuardianService guardian;
    private final PersonalLoopStore store;
    private final PersonalTimelineRecorder timeline;
    private final Clock clock;

    public IncidentCommanderService(HomelabGuardianService guardian, PersonalLoopStore store,
            PersonalTimelineRecorder timeline, Clock clock) {
        this.guardian = Objects.requireNonNull(guardian); this.store = Objects.requireNonNull(store);
        this.timeline = Objects.requireNonNull(timeline); this.clock = Objects.requireNonNull(clock);
    }

    public Inspection inspect(String ownerId) {
        String owner = PersonalLoopModels.owner(ownerId);
        GuardianReport report = guardian.inspect();
        if (report.healthy()) {
            List<Incident> resolved = store.incidents(owner, 100).stream()
                    .filter(value -> value.status() == IncidentStatus.OPEN).map(value -> resolve(owner, value.id(), "Guardian returned UP")).toList();
            return new Inspection(report.status(), null, resolved);
        }
        String fingerprint = fingerprint(report.findings());
        store.incidents(owner, 100).stream().filter(value -> value.status() == IncidentStatus.OPEN)
                .filter(value -> !value.fingerprint().equals(fingerprint))
                .forEach(value -> resolve(owner, value.id(), "condition fingerprint changed"));
        Instant now = clock.instant();
        Incident incident = store.openIncident(owner, fingerprint).map(current -> update(current, report, now))
                .orElseGet(() -> open(owner, fingerprint, report, now));
        store.save(incident);
        timeline.record(owner, "INCIDENT_OBSERVED", "INCIDENT", incident.id().toString(),
                "Homelab incident: " + incident.severity(), incident.summary(),
                Map.of("fingerprint", fingerprint, "probable_cause", incident.probableCause()), now);
        return new Inspection(report.status(), incident, List.of());
    }

    public Incident resolve(String ownerId, UUID id, String note) {
        String owner = PersonalLoopModels.owner(ownerId);
        Incident current = store.incident(Objects.requireNonNull(id), owner)
                .orElseThrow(() -> new IllegalArgumentException("incident was not found"));
        if (current.status() == IncidentStatus.RESOLVED) return current;
        Instant now = clock.instant();
        List<Map<String, Object>> events = append(current.timeline(), event("RESOLVED", note, now));
        Incident resolved = store.save(new Incident(current.id(), current.ownerId(), current.fingerprint(),
                IncidentStatus.RESOLVED, current.severity(), current.summary(), current.probableCause(),
                current.findings(), events, current.openedAt(), now, now));
        timeline.record(owner, "INCIDENT_RESOLVED", "INCIDENT", id.toString(), "Homelab incident resolved",
                Objects.requireNonNullElse(note, ""), Map.of("fingerprint", current.fingerprint()), now);
        return resolved;
    }

    public List<Incident> list(String ownerId, int limit) { if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500"); return store.incidents(PersonalLoopModels.owner(ownerId), limit); }

    private Incident open(String owner, String fingerprint, GuardianReport report, Instant now) {
        return new Incident(UUID.randomUUID(), owner, fingerprint, IncidentStatus.OPEN, report.status(),
                summary(report.findings()), probableCause(report.findings()), findings(report.findings()),
                List.of(event("OPENED", "Guardian correlated " + report.findings().size() + " findings", now)),
                now, now, null);
    }

    private Incident update(Incident current, GuardianReport report, Instant now) {
        List<Map<String, Object>> mapped = findings(report.findings());
        boolean changed = !mapped.equals(current.findings()) || !report.status().equals(current.severity());
        List<Map<String, Object>> events = changed
                ? append(current.timeline(), event("UPDATED", "Evidence or severity changed", now))
                : current.timeline();
        return new Incident(current.id(), current.ownerId(), current.fingerprint(), IncidentStatus.OPEN,
                report.status(), summary(report.findings()), probableCause(report.findings()), mapped, events,
                current.openedAt(), now, null);
    }

    private String probableCause(List<GuardianFinding> findings) {
        return findings.stream().max(Comparator.comparing(GuardianFinding::severity))
                .map(value -> value.component() + " [" + value.causeConfidence() + "]: " + value.cause())
                .orElse("No deterministic cause identified");
    }

    private String summary(List<GuardianFinding> findings) {
        return findings.stream().limit(3).map(value -> value.component() + ": " + value.summary())
                .collect(java.util.stream.Collectors.joining("; "));
    }

    private List<Map<String, Object>> findings(List<GuardianFinding> values) {
        return values.stream().map(value -> {
            Map<String, Object> finding = new LinkedHashMap<>(); finding.put("code", value.code());
            finding.put("severity", value.severity().name()); finding.put("component", value.component());
            finding.put("summary", value.summary()); finding.put("evidence", value.evidence());
            finding.put("cause", value.cause()); finding.put("cause_confidence", value.causeConfidence());
            finding.put("recommended_action", value.recommendedAction()); return Map.copyOf(finding);
        }).toList();
    }

    private Map<String, Object> event(String type, String detail, Instant at) { return Map.of("type", type, "detail", Objects.requireNonNullElse(detail, ""), "at", at.toString()); }
    private List<Map<String, Object>> append(List<Map<String, Object>> source, Map<String, Object> value) { List<Map<String, Object>> copy = new ArrayList<>(source); copy.add(value); return copy.size() <= 50 ? List.copyOf(copy) : List.copyOf(copy.subList(copy.size() - 50, copy.size())); }
    private String fingerprint(List<GuardianFinding> findings) { String text = findings.stream().map(v -> v.severity() + ":" + v.code() + ":" + v.component()).sorted().collect(java.util.stream.Collectors.joining("|")); try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0, 24); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); } }

    public record Inspection(String guardianStatus, Incident incident, List<Incident> resolved) { public Inspection { resolved = List.copyOf(resolved); } }
}
