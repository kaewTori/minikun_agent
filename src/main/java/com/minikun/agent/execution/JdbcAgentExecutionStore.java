package com.minikun.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "minikun.agent.execution.enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcAgentExecutionStore implements AgentExecutionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAgentExecutionStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc template must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
    }

    @Override
    public AgentRun createRun(AgentRun run) {
        jdbc.update("""
                INSERT INTO minikun_agent_run
                    (id, owner_id, conversation_id, response_id, objective, planned_steps_json, risk_level, risk_reasons_json, status, current_step,
                     max_steps, summary, failure_reason, created_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, run.id(), run.ownerId(), run.conversationId(), run.responseId(), run.objective(), json(run.plannedSteps()),
                run.riskAssessment().level().name(), json(run.riskAssessment().reasons()), run.status().name(), run.currentStep(), run.maxSteps(), run.summary(), run.failureReason(),
                timestamp(run.createdAt()), timestamp(run.updatedAt()), timestamp(run.completedAt()));
        return run;
    }

    @Override
    public AgentRun updateRun(AgentRun run) {
        jdbc.update("""
                UPDATE minikun_agent_run SET status = ?, current_step = ?, summary = ?, failure_reason = ?,
                    updated_at = ?, completed_at = ? WHERE id = ? AND owner_id = ?
                """, run.status().name(), run.currentStep(), run.summary(), run.failureReason(),
                timestamp(run.updatedAt()), timestamp(run.completedAt()), run.id(), run.ownerId());
        return run;
    }

    @Override
    public Optional<AgentRun> findRun(UUID id) {
        return jdbc.query("SELECT * FROM minikun_agent_run WHERE id = ?", this::mapRun, id)
                .stream().findFirst();
    }

    @Override
    public Optional<AgentRun> findRun(UUID id, String ownerId) {
        return jdbc.query("""
                SELECT * FROM minikun_agent_run WHERE id = ? AND owner_id = ?
                """, this::mapRun, id, ownerId).stream().findFirst();
    }

    @Override
    public List<AgentRun> listRuns(String ownerId, AgentRunStatus status, int limit) {
        if (status == null) {
            return jdbc.query("""
                    SELECT * FROM minikun_agent_run WHERE owner_id = ? ORDER BY created_at DESC LIMIT ?
                    """, this::mapRun, ownerId, limit);
        }
        return jdbc.query("""
                SELECT * FROM minikun_agent_run WHERE owner_id = ? AND status = ?
                ORDER BY created_at DESC LIMIT ?
                """, this::mapRun, ownerId, status.name(), limit);
    }

    @Override
    public List<AgentRun> listRuns(String ownerId, String conversationId, AgentRunStatus status, int limit) {
        if (status == null) {
            return jdbc.query("""
                    SELECT * FROM minikun_agent_run WHERE owner_id = ? AND conversation_id = ?
                    ORDER BY created_at DESC LIMIT ?
                    """, this::mapRun, ownerId, conversationId, limit);
        }
        return jdbc.query("""
                SELECT * FROM minikun_agent_run WHERE owner_id = ? AND conversation_id = ? AND status = ?
                ORDER BY created_at DESC LIMIT ?
                """, this::mapRun, ownerId, conversationId, status.name(), limit);
    }

    @Override
    public AgentExecutionStep createStep(AgentExecutionStep step) {
        jdbc.update("""
                INSERT INTO minikun_agent_step
                    (id, run_id, step_index, tool_call_id, tool_name, arguments_json, status, attempts,
                     result_json, error_code, error, started_at, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, step.id(), step.runId(), step.stepIndex(), step.toolCallId(), step.toolName(),
                step.argumentsJson(), step.status().name(), step.attempts(), step.resultJson(), step.errorCode(),
                step.error(), timestamp(step.startedAt()), timestamp(step.updatedAt()), timestamp(step.completedAt()));
        return step;
    }

    @Override
    public AgentExecutionStep updateStep(AgentExecutionStep step) {
        jdbc.update("""
                UPDATE minikun_agent_step SET status = ?, attempts = ?, result_json = ?, error_code = ?,
                    error = ?, updated_at = ?, completed_at = ? WHERE id = ? AND run_id = ?
                """, step.status().name(), step.attempts(), step.resultJson(), step.errorCode(), step.error(),
                timestamp(step.updatedAt()), timestamp(step.completedAt()), step.id(), step.runId());
        return step;
    }

    @Override
    public Optional<AgentExecutionStep> findStep(UUID runId, String toolCallId) {
        return jdbc.query("""
                SELECT * FROM minikun_agent_step WHERE run_id = ? AND tool_call_id = ?
                """, this::mapStep, runId, toolCallId).stream().findFirst();
    }

    @Override
    public List<AgentExecutionStep> listSteps(UUID runId) {
        return jdbc.query("""
                SELECT * FROM minikun_agent_step WHERE run_id = ? ORDER BY step_index, started_at
                """, this::mapStep, runId);
    }

    private AgentRun mapRun(ResultSet rs, int row) throws SQLException {
        return new AgentRun(uuid(rs, "id"), rs.getString("owner_id"), rs.getString("conversation_id"), rs.getString("response_id"),
                rs.getString("objective"), steps(rs.getString("planned_steps_json")),
                new AgentRiskAssessment(AgentRiskLevel.valueOf(rs.getString("risk_level")),
                        steps(rs.getString("risk_reasons_json"))),
                AgentRunStatus.valueOf(rs.getString("status")), rs.getInt("current_step"),
                rs.getInt("max_steps"), rs.getString("summary"), rs.getString("failure_reason"),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "completed_at"));
    }

    private AgentExecutionStep mapStep(ResultSet rs, int row) throws SQLException {
        return new AgentExecutionStep(uuid(rs, "id"), uuid(rs, "run_id"), rs.getInt("step_index"),
                rs.getString("tool_call_id"), rs.getString("tool_name"), rs.getString("arguments_json"),
                AgentStepStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getString("result_json"), rs.getString("error_code"), rs.getString("error"),
                instant(rs, "started_at"), instant(rs, "updated_at"), instant(rs, "completed_at"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not serialize agent execution state", exception);
        }
    }

    private List<String> steps(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not read agent plan", exception);
        }
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : UUID.fromString(value.toString());
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
