package com.minikun.agent.minikun_agent.conversation;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL-backed summary store with an in-process fallback for database-free operation. */
public final class ResilientConversationSummaryStore implements ConversationSummaryStore {
    private final ObjectProvider<JdbcTemplate> jdbcProvider;
    private final ConcurrentMap<Scope, ConversationSummary> fallback = new ConcurrentHashMap<>();

    public ResilientConversationSummaryStore(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbcProvider = java.util.Objects.requireNonNull(jdbcProvider, "jdbc provider must not be null");
    }

    @Override
    public Optional<ConversationSummary> find(String ownerId, ConversationId conversationId) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                Optional<ConversationSummary> persisted = jdbc.query("""
                        SELECT owner_id, conversation_id, content, covered_fingerprints,
                               summarized_messages, updated_at
                        FROM minikun_conversation_summary
                        WHERE owner_id = ? AND conversation_id = ?
                        """, (resultSet, row) -> new ConversationSummary(
                                resultSet.getString("owner_id"),
                                new ConversationId(resultSet.getString("conversation_id")),
                                resultSet.getString("content"),
                                fingerprints(resultSet.getString("covered_fingerprints")),
                                resultSet.getInt("summarized_messages"),
                                resultSet.getTimestamp("updated_at").toInstant()),
                        ownerId, conversationId.value()).stream().findFirst();
                if (persisted.isPresent()) {
                    return persisted;
                }
            } catch (RuntimeException ignored) {
                // SQL initialization may be disabled; use the local fallback.
            }
        }
        return Optional.ofNullable(fallback.get(new Scope(ownerId, conversationId.value())));
    }

    @Override
    public void save(ConversationSummary summary) {
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                jdbc.update("""
                        INSERT INTO minikun_conversation_summary
                            (owner_id, conversation_id, content, covered_fingerprints,
                             summarized_messages, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (owner_id, conversation_id) DO UPDATE SET
                            content = EXCLUDED.content,
                            covered_fingerprints = EXCLUDED.covered_fingerprints,
                            summarized_messages = EXCLUDED.summarized_messages,
                            updated_at = EXCLUDED.updated_at
                        """, summary.ownerId(), summary.conversationId().value(), summary.content(),
                        String.join("\n", summary.coveredFingerprints()), summary.summarizedMessages(),
                        Timestamp.from(summary.updatedAt()));
                fallback.put(new Scope(summary.ownerId(), summary.conversationId().value()), summary);
                return;
            } catch (RuntimeException ignored) {
                // SQL initialization may be disabled; use the local fallback.
            }
        }
        fallback.put(new Scope(summary.ownerId(), summary.conversationId().value()), summary);
    }

    @Override
    public boolean delete(String ownerId, ConversationId conversationId) {
        boolean deleted = false;
        JdbcTemplate jdbc = jdbcProvider.getIfAvailable();
        if (jdbc != null) {
            try {
                deleted = jdbc.update("""
                        DELETE FROM minikun_conversation_summary
                        WHERE owner_id = ? AND conversation_id = ?
                        """, ownerId, conversationId.value()) > 0;
            } catch (RuntimeException ignored) {
                // SQL initialization may be disabled; still clear the local fallback.
            }
        }
        return fallback.remove(new Scope(ownerId, conversationId.value())) != null || deleted;
    }

    private List<String> fingerprints(String serialized) {
        if (serialized == null || serialized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Arrays.stream(serialized.split("\\R"))
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .forEach(values::add);
        return List.copyOf(values);
    }

    private record Scope(String ownerId, String conversationId) {
    }
}
