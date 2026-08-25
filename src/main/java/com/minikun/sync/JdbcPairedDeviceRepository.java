package com.minikun.sync;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public final class JdbcPairedDeviceRepository implements PairedDeviceRepository {
    private final JdbcTemplate jdbc;

    public JdbcPairedDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long activeCount(Instant now) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM minikun_paired_device
                WHERE revoked_at IS NULL AND expires_at > ?
                """, Long.class, timestamp(now));
        return count == null ? 0 : count;
    }

    @Override
    public Optional<PairedDevice> findActiveByTokenHash(String tokenHash, Instant now) {
        return jdbc.query("""
                SELECT id, owner_id, name, token_hash, created_at, last_seen_at, expires_at, revoked_at
                FROM minikun_paired_device
                WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?
                """, this::map, tokenHash, timestamp(now)).stream().findFirst();
    }

    @Override
    public void save(PairedDevice device) {
        jdbc.update("""
                INSERT INTO minikun_paired_device
                    (id, owner_id, name, token_hash, created_at, last_seen_at, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, device.id(), device.ownerId(), device.name(), device.tokenHash(),
                timestamp(device.createdAt()), timestamp(device.lastSeenAt()), timestamp(device.expiresAt()),
                device.revokedAt() == null ? null : timestamp(device.revokedAt()));
    }

    @Override
    public void touch(UUID deviceId, Instant seenAt) {
        jdbc.update("UPDATE minikun_paired_device SET last_seen_at = ? WHERE id = ? AND revoked_at IS NULL",
                timestamp(seenAt), deviceId);
    }

    @Override
    public List<PairedDevice> list(String ownerId) {
        return jdbc.query("""
                SELECT id, owner_id, name, token_hash, created_at, last_seen_at, expires_at, revoked_at
                FROM minikun_paired_device
                WHERE owner_id = ? AND revoked_at IS NULL
                ORDER BY last_seen_at DESC
                """, this::map, ownerId);
    }

    @Override
    public boolean revoke(String ownerId, UUID deviceId, Instant revokedAt) {
        return jdbc.update("""
                UPDATE minikun_paired_device SET revoked_at = ?
                WHERE owner_id = ? AND id = ? AND revoked_at IS NULL
                """, timestamp(revokedAt), ownerId, deviceId) > 0;
    }

    private PairedDevice map(ResultSet rs, int row) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new PairedDevice(rs.getObject("id", UUID.class), rs.getString("owner_id"),
                rs.getString("name"), rs.getString("token_hash"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(), revoked == null ? null : revoked.toInstant());
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
