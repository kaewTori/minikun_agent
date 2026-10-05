package com.minikun.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Durable checkpoints and exact-resource grants, with optimistic concurrency on decisions. */
public final class AgentActionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public AgentActionStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public void create(String owner, String key, AgentActionCheckpoint checkpoint) {
        jdbc.update("INSERT INTO minikun_action_checkpoint (run_id,owner_id,dedup_key,state_json,revision) VALUES (?,?,?,?,0)",
                checkpoint.runId(), owner, key == null || key.isBlank() ? null : key, json(checkpoint));
    }
    public Optional<AgentActionCheckpoint> find(String owner, UUID id) {
        return jdbc.query("SELECT state_json FROM minikun_action_checkpoint WHERE owner_id=? AND run_id=?",
                (rs, row) -> read(rs.getString(1), AgentActionCheckpoint.class), owner, id).stream().findFirst();
    }
    public Optional<AgentActionCheckpoint> byKey(String owner, String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        return jdbc.query("SELECT state_json FROM minikun_action_checkpoint WHERE owner_id=? AND dedup_key=?",
                (rs, row) -> read(rs.getString(1), AgentActionCheckpoint.class), owner, key).stream().findFirst();
    }
    public AgentActionCheckpoint save(String owner, AgentActionCheckpoint state) {
        var updated = state.revised(state.revision() + 1);
        if (jdbc.update("UPDATE minikun_action_checkpoint SET state_json=?,revision=? WHERE run_id=? AND owner_id=? AND revision=?",
                json(updated), updated.revision(), state.runId(), owner, state.revision()) != 1) throw new IllegalStateException("action changed; refresh before deciding");
        return updated;
    }
    public List<AgentActionCheckpoint> active() {
        return jdbc.query("SELECT c.state_json FROM minikun_action_checkpoint c JOIN minikun_agent_run r ON r.id=c.run_id WHERE r.status IN ('PLANNED','RUNNING','WAITING_CONFIRMATION') AND (r.status<>'WAITING_CONFIRMATION' OR c.state_json::jsonb->>'phase'<>'WAITING') ORDER BY r.created_at LIMIT 20",
                (rs, row) -> read(rs.getString(1), AgentActionCheckpoint.class));
    }

    public record Grant(UUID id, String ownerId, String tool, String action, String resource, Instant expiresAt,
            int maxUses, int uses, int cooldownSeconds, Instant lastUsedAt, String monitorComponent) { }

    public Grant grant(String owner, String tool, String action, String resource, Instant expiry, int maxUses, int cooldown, String monitor) {
        if (expiry == null || maxUses < 1 || maxUses > 1000 || cooldown < 0 || cooldown > 86400
                || resource == null || resource.isBlank() || resource.contains("*")) throw new IllegalArgumentException("grant requires an exact resource, expiry, 1–1000 uses and cooldown 0–86400");
        var grant = new Grant(UUID.randomUUID(), owner, tool, action, resource, expiry, maxUses, 0, cooldown, null, monitor == null ? "" : monitor);
        jdbc.update("INSERT INTO minikun_action_grant (id,owner_id,tool,action,resource,expires_at,max_uses,uses,cooldown_seconds,monitor_component) VALUES (?,?,?,?,?,?,?,0,?,?)",
                grant.id(), owner, tool, action, resource, java.sql.Timestamp.from(expiry), maxUses, cooldown, grant.monitorComponent());
        return grant;
    }
    public List<Grant> grants(String owner) {
        return jdbc.query("SELECT * FROM minikun_action_grant WHERE owner_id=? AND revoked_at IS NULL ORDER BY expires_at DESC", (rs, row) ->
                new Grant(UUID.fromString(rs.getString("id")), rs.getString("owner_id"), rs.getString("tool"), rs.getString("action"), rs.getString("resource"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getInt("max_uses"), rs.getInt("uses"), rs.getInt("cooldown_seconds"), rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant(), rs.getString("monitor_component")), owner);
    }
    public List<Grant> monitors(Instant now) {
        return jdbc.query("SELECT DISTINCT owner_id FROM minikun_action_grant WHERE monitor_component<>'' AND revoked_at IS NULL AND expires_at>?",
                (rs, row) -> rs.getString(1), java.sql.Timestamp.from(now)).stream().flatMap(owner -> grants(owner).stream())
                .filter(g -> !g.monitorComponent().isBlank() && g.expiresAt().isAfter(now) && g.uses() < g.maxUses()).toList();
    }
    public boolean consume(String owner, String tool, String action, String resource, Instant now) {
        for (Grant grant : grants(owner)) {
            if (!grant.tool().equals(tool) || !grant.action().equals(action) || !grant.resource().equals(resource)) continue;
            if (jdbc.update("UPDATE minikun_action_grant SET uses=uses+1,last_used_at=? WHERE id=? AND owner_id=? AND revoked_at IS NULL AND expires_at>? AND uses<max_uses AND (last_used_at IS NULL OR last_used_at<=?)",
                    java.sql.Timestamp.from(now), grant.id(), owner, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.minusSeconds(grant.cooldownSeconds()))) == 1) return true;
        }
        return false;
    }
    public void revoke(String owner, UUID id, Instant now) {
        if (jdbc.update("UPDATE minikun_action_grant SET revoked_at=? WHERE owner_id=? AND id=? AND revoked_at IS NULL", java.sql.Timestamp.from(now), owner, id) != 1) throw new IllegalArgumentException("grant not found");
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("cannot persist action", e); } }
    private <T> T read(String value, Class<T> type) { try { return mapper.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("cannot read action", e); } }
}
