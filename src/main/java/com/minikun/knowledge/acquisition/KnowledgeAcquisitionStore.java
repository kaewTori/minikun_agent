package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed acquisition ledger with an in-memory fallback for focused tests. */
public final class KnowledgeAcquisitionStore {
    private static final String TOPIC_COLUMNS = "id, owner_id, name, objective, origin, priority, refresh_policy, "
            + "source_policy, trusted_domains, status, next_run_at, last_run_at, created_at, updated_at";
    private static final String RUN_COLUMNS = "id, topic_id, owner_id, status, trigger_type, objective, trace, "
            + "stop_reason, source_count, candidate_count, published_count, last_error, started_at, completed_at";
    private static final String CLAIM_COLUMNS = "id, topic_id, owner_id, topic_name, claim_text, fingerprint, "
            + "status, confidence, evidence_urls, verification_reason, embedding, embedding_model, discovered_at, "
            + "verified_at, published_at, expires_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Map<UUID, Topic> topics = new ConcurrentHashMap<>();
    private final Map<UUID, AcquisitionRun> runs = new ConcurrentHashMap<>();
    private final Map<UUID, ExternalSource> sources = new ConcurrentHashMap<>();
    private final Map<UUID, Claim> claims = new ConcurrentHashMap<>();

    public KnowledgeAcquisitionStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = java.util.Objects.requireNonNull(json, "object mapper must not be null");
    }

    public Topic saveTopic(Topic value) {
        if (jdbc == null) {
            topics.put(value.id(), value);
            return value;
        }
        jdbc.update("""
                INSERT INTO minikun_knowledge_topic
                    (id, owner_id, name, objective, origin, priority, refresh_policy, source_policy,
                     trusted_domains, status, next_run_at, last_run_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name, objective = EXCLUDED.objective, origin = EXCLUDED.origin,
                    priority = EXCLUDED.priority, refresh_policy = EXCLUDED.refresh_policy,
                    source_policy = EXCLUDED.source_policy, trusted_domains = EXCLUDED.trusted_domains,
                    status = EXCLUDED.status, next_run_at = EXCLUDED.next_run_at,
                    last_run_at = EXCLUDED.last_run_at, updated_at = EXCLUDED.updated_at
                """, value.id(), value.ownerId(), value.name(), value.objective(), value.origin().name(),
                value.priority(), value.refreshPolicy().name(), value.sourcePolicy().name(),
                write(value.trustedDomains()), value.status().name(), ts(value.nextRunAt()),
                ts(value.lastRunAt()), ts(value.createdAt()), ts(value.updatedAt()));
        return value;
    }

    public Optional<Topic> topic(String ownerId, UUID id) {
        if (jdbc == null) return Optional.ofNullable(topics.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT " + TOPIC_COLUMNS
                + " FROM minikun_knowledge_topic WHERE owner_id = ? AND id = ?", this::topic, ownerId, id)
                .stream().findFirst();
    }

    public List<Topic> topics(String ownerId, TopicStatus status, int limit) {
        if (jdbc == null) {
            return topics.values().stream().filter(v -> v.ownerId().equals(ownerId))
                    .filter(v -> status == null || v.status() == status)
                    .sorted(Comparator.comparingInt(Topic::priority).reversed()
                            .thenComparing(Topic::updatedAt, Comparator.reverseOrder()))
                    .limit(limit).toList();
        }
        if (status == null) {
            return jdbc.query("SELECT " + TOPIC_COLUMNS + " FROM minikun_knowledge_topic "
                    + "WHERE owner_id = ? ORDER BY priority DESC, updated_at DESC LIMIT ?", this::topic,
                    ownerId, limit);
        }
        return jdbc.query("SELECT " + TOPIC_COLUMNS + " FROM minikun_knowledge_topic "
                + "WHERE owner_id = ? AND status = ? ORDER BY priority DESC, updated_at DESC LIMIT ?",
                this::topic, ownerId, status.name(), limit);
    }

    public List<Topic> due(String ownerId, Instant now, int limit) {
        if (jdbc == null) {
            return topics.values().stream().filter(v -> v.ownerId().equals(ownerId))
                    .filter(v -> v.status() == TopicStatus.ACTIVE && v.nextRunAt() != null
                            && !v.nextRunAt().isAfter(now))
                    .sorted(Comparator.comparingInt(Topic::priority).reversed()
                            .thenComparing(Topic::nextRunAt)).limit(limit).toList();
        }
        return jdbc.query("SELECT " + TOPIC_COLUMNS + " FROM minikun_knowledge_topic "
                + "WHERE owner_id = ? AND status = 'ACTIVE' AND next_run_at IS NOT NULL AND next_run_at <= ? "
                + "ORDER BY priority DESC, next_run_at LIMIT ?", this::topic, ownerId, ts(now), limit);
    }

    public boolean deleteTopic(String ownerId, UUID id) {
        if (jdbc == null) {
            Topic value = topics.get(id);
            if (value == null || !value.ownerId().equals(ownerId)) return false;
            topics.remove(id);
            runs.values().removeIf(run -> run.topicId().equals(id));
            sources.values().removeIf(source -> source.topicId().equals(id));
            claims.values().removeIf(claim -> claim.topicId().equals(id));
            return true;
        }
        return jdbc.update("DELETE FROM minikun_knowledge_topic WHERE owner_id = ? AND id = ?", ownerId, id) > 0;
    }

    public AcquisitionRun saveRun(AcquisitionRun value) {
        if (jdbc == null) {
            runs.put(value.id(), value);
            return value;
        }
        jdbc.update("""
                INSERT INTO minikun_knowledge_acquisition_run
                    (id, topic_id, owner_id, status, trigger_type, objective, trace, stop_reason,
                     source_count, candidate_count, published_count, last_error, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, trace = EXCLUDED.trace,
                    stop_reason = EXCLUDED.stop_reason, source_count = EXCLUDED.source_count,
                    candidate_count = EXCLUDED.candidate_count, published_count = EXCLUDED.published_count,
                    last_error = EXCLUDED.last_error, completed_at = EXCLUDED.completed_at
                """, value.id(), value.topicId(), value.ownerId(), value.status().name(), value.trigger().name(),
                value.objective(), value.trace(), value.stopReason(), value.sourceCount(), value.candidateCount(),
                value.publishedCount(), value.error(), ts(value.startedAt()), ts(value.completedAt()));
        return value;
    }

    public List<AcquisitionRun> runs(String ownerId, UUID topicId, int limit) {
        if (jdbc == null) {
            return runs.values().stream().filter(v -> v.ownerId().equals(ownerId))
                    .filter(v -> topicId == null || v.topicId().equals(topicId))
                    .sorted(Comparator.comparing(AcquisitionRun::startedAt).reversed()).limit(limit).toList();
        }
        if (topicId == null) {
            return jdbc.query("SELECT " + RUN_COLUMNS + " FROM minikun_knowledge_acquisition_run "
                    + "WHERE owner_id = ? ORDER BY started_at DESC LIMIT ?", this::run, ownerId, limit);
        }
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM minikun_knowledge_acquisition_run "
                + "WHERE owner_id = ? AND topic_id = ? ORDER BY started_at DESC LIMIT ?", this::run,
                ownerId, topicId, limit);
    }

    public ExternalSource saveSource(ExternalSource value) {
        if (jdbc == null) {
            sources.put(value.id(), value);
            return value;
        }
        jdbc.update("""
                INSERT INTO minikun_external_source
                    (id, run_id, topic_id, owner_id, source_url, source_kind, content_excerpt,
                     content_hash, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (topic_id, source_url, content_hash) DO NOTHING
                """, value.id(), value.runId(), value.topicId(), value.ownerId(), value.url(),
                value.kind().name(), value.contentExcerpt(), value.contentHash(), ts(value.fetchedAt()));
        return value;
    }

    public synchronized Claim saveClaim(Claim proposed) {
        Optional<Claim> existing = claimByFingerprint(proposed.topicId(), proposed.fingerprint());
        Claim value = existing.map(current -> merge(current, proposed)).orElse(proposed);
        if (jdbc == null) {
            claims.put(value.id(), value);
            return value;
        }
        jdbc.update("""
                INSERT INTO minikun_knowledge_claim
                    (id, topic_id, owner_id, topic_name, claim_text, fingerprint, status, confidence,
                     evidence_urls, verification_reason, embedding, embedding_model, discovered_at,
                     verified_at, published_at, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (topic_id, fingerprint) DO UPDATE SET
                    claim_text = EXCLUDED.claim_text, status = EXCLUDED.status,
                    confidence = EXCLUDED.confidence, evidence_urls = EXCLUDED.evidence_urls,
                    verification_reason = EXCLUDED.verification_reason, embedding = EXCLUDED.embedding,
                    embedding_model = EXCLUDED.embedding_model, verified_at = EXCLUDED.verified_at,
                    published_at = EXCLUDED.published_at, expires_at = EXCLUDED.expires_at,
                    updated_at = EXCLUDED.updated_at
                """, value.id(), value.topicId(), value.ownerId(), value.topicName(), value.text(),
                value.fingerprint(), value.status().name(), value.confidence(), write(value.evidenceUrls()),
                value.verificationReason(), value.embedding(), value.embeddingModel(), ts(value.discoveredAt()),
                ts(value.verifiedAt()), ts(value.publishedAt()), ts(value.expiresAt()), ts(value.updatedAt()));
        return value;
    }

    public Optional<Claim> claim(String ownerId, UUID id) {
        if (jdbc == null) return Optional.ofNullable(claims.get(id)).filter(v -> v.ownerId().equals(ownerId));
        return jdbc.query("SELECT " + CLAIM_COLUMNS
                + " FROM minikun_knowledge_claim WHERE owner_id = ? AND id = ?", this::claim, ownerId, id)
                .stream().findFirst();
    }

    public List<Claim> claims(String ownerId, ClaimStatus status, int limit) {
        if (jdbc == null) {
            return claims.values().stream().filter(v -> v.ownerId().equals(ownerId))
                    .filter(v -> status == null || v.status() == status)
                    .sorted(Comparator.comparing(Claim::updatedAt).reversed()).limit(limit).toList();
        }
        if (status == null) {
            return jdbc.query("SELECT " + CLAIM_COLUMNS + " FROM minikun_knowledge_claim "
                    + "WHERE owner_id = ? ORDER BY updated_at DESC LIMIT ?", this::claim, ownerId, limit);
        }
        return jdbc.query("SELECT " + CLAIM_COLUMNS + " FROM minikun_knowledge_claim "
                + "WHERE owner_id = ? AND status = ? ORDER BY updated_at DESC LIMIT ?", this::claim,
                ownerId, status.name(), limit);
    }

    public List<Claim> published(String ownerId, Instant now, int limit) {
        if (jdbc == null) {
            return claims.values().stream().filter(v -> v.ownerId().equals(ownerId))
                    .filter(v -> v.status() == ClaimStatus.PUBLISHED)
                    .filter(v -> v.expiresAt() == null || v.expiresAt().isAfter(now))
                    .sorted(Comparator.comparing(Claim::updatedAt).reversed()).limit(limit).toList();
        }
        return jdbc.query("SELECT " + CLAIM_COLUMNS + " FROM minikun_knowledge_claim "
                + "WHERE owner_id = ? AND status = 'PUBLISHED' AND (expires_at IS NULL OR expires_at > ?) "
                + "ORDER BY updated_at DESC LIMIT ?", this::claim, ownerId, ts(now), limit);
    }

    public int markExpired(Instant now) {
        if (jdbc == null) {
            int changed = 0;
            for (Claim value : new ArrayList<>(claims.values())) {
                if (value.status() == ClaimStatus.PUBLISHED && value.expiresAt() != null
                        && !value.expiresAt().isAfter(now)) {
                    claims.put(value.id(), value.withStatus(ClaimStatus.STALE, now));
                    changed++;
                }
            }
            return changed;
        }
        return jdbc.update("UPDATE minikun_knowledge_claim SET status = 'STALE', updated_at = ? "
                + "WHERE status = 'PUBLISHED' AND expires_at IS NOT NULL AND expires_at <= ?", ts(now), ts(now));
    }

    private Optional<Claim> claimByFingerprint(UUID topicId, String fingerprint) {
        if (jdbc == null) return claims.values().stream()
                .filter(v -> v.topicId().equals(topicId) && v.fingerprint().equals(fingerprint)).findFirst();
        return jdbc.query("SELECT " + CLAIM_COLUMNS
                + " FROM minikun_knowledge_claim WHERE topic_id = ? AND fingerprint = ?",
                this::claim, topicId, fingerprint).stream().findFirst();
    }

    private Claim merge(Claim current, Claim proposed) {
        // A proposal carrying the durable id is an explicit management review, not an agent re-discovery.
        if (current.id().equals(proposed.id())) return proposed;
        LinkedHashSet<String> evidence = new LinkedHashSet<>(current.evidenceUrls());
        evidence.addAll(proposed.evidenceUrls());
        ClaimStatus status = switch (current.status()) {
            case RETRACTED, DISPUTED -> current.status();
            case PUBLISHED -> ClaimStatus.PUBLISHED;
            default -> proposed.status();
        };
        return new Claim(current.id(), current.topicId(), current.ownerId(), current.topicName(),
                proposed.text(), current.fingerprint(), status, Math.max(current.confidence(), proposed.confidence()),
                List.copyOf(evidence), proposed.verificationReason(), proposed.embedding(), proposed.embeddingModel(),
                current.discoveredAt(), proposed.verifiedAt() == null ? current.verifiedAt() : proposed.verifiedAt(),
                proposed.publishedAt() == null ? current.publishedAt() : proposed.publishedAt(),
                proposed.expiresAt(), proposed.updatedAt());
    }

    private Topic topic(ResultSet rs, int row) throws SQLException {
        return new Topic(uuid(rs, "id"), rs.getString("owner_id"), rs.getString("name"),
                rs.getString("objective"), TopicOrigin.valueOf(rs.getString("origin")), rs.getInt("priority"),
                RefreshPolicy.valueOf(rs.getString("refresh_policy")),
                SourcePolicy.valueOf(rs.getString("source_policy")), readList(rs.getString("trusted_domains")),
                TopicStatus.valueOf(rs.getString("status")), instant(rs, "next_run_at"),
                instant(rs, "last_run_at"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private AcquisitionRun run(ResultSet rs, int row) throws SQLException {
        return new AcquisitionRun(uuid(rs, "id"), uuid(rs, "topic_id"), rs.getString("owner_id"),
                RunStatus.valueOf(rs.getString("status")), RunTrigger.valueOf(rs.getString("trigger_type")),
                rs.getString("objective"), rs.getString("trace"), rs.getString("stop_reason"),
                rs.getInt("source_count"), rs.getInt("candidate_count"), rs.getInt("published_count"),
                rs.getString("last_error"), instant(rs, "started_at"), instant(rs, "completed_at"));
    }

    private Claim claim(ResultSet rs, int row) throws SQLException {
        return new Claim(uuid(rs, "id"), uuid(rs, "topic_id"), rs.getString("owner_id"),
                rs.getString("topic_name"), rs.getString("claim_text"), rs.getString("fingerprint"),
                ClaimStatus.valueOf(rs.getString("status")), rs.getDouble("confidence"),
                readList(rs.getString("evidence_urls")), rs.getString("verification_reason"),
                rs.getString("embedding"), rs.getString("embedding_model"), instant(rs, "discovered_at"),
                instant(rs, "verified_at"), instant(rs, "published_at"), instant(rs, "expires_at"),
                instant(rs, "updated_at"));
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    private String write(List<String> values) {
        try { return json.writeValueAsString(values == null ? List.of() : values); }
        catch (Exception exception) { throw new IllegalStateException("knowledge metadata could not be encoded", exception); }
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return List.copyOf(json.readValue(value, new TypeReference<List<String>>() { })); }
        catch (Exception exception) { throw new IllegalStateException("knowledge metadata could not be decoded", exception); }
    }
}
