package com.minikun.agent.execution;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.*;
import java.util.*;
import org.springframework.core.annotation.Order;

@Order(3)
public final class AgentActionConfirmationRouter implements ToolRequestRouter {
    private final AgentActionRuntime runtime;
    private final AgentExecutionService executions;
    public AgentActionConfirmationRouter(AgentActionRuntime runtime, AgentExecutionService executions) { this.runtime = runtime; this.executions = executions; }
    @Override public Optional<ToolEvidence> route(String text, ConversationId conversation) { return route(text, conversation, "default"); }
    @Override public Optional<ToolEvidence> route(String text, ConversationId conversation, String owner) {
        String normalized = Objects.toString(text, "").strip().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "").replaceAll("(ครับ|ค่ะ|คะ)$", "").strip();
        boolean approve = Set.of("ยืนยัน", "ทำเลย", "ตกลง", "confirm", "yes", "ok").contains(normalized);
        boolean reject = Set.of("ยกเลิก", "ไม่ทำ", "ไม่อนุมัติ", "cancel", "reject", "no").contains(normalized);
        if (!approve && !reject) return Optional.empty();
        var pending = executions.list(owner, conversation.value(), AgentRunStatus.WAITING_CONFIRMATION, 50).stream().filter(run -> runtime.hasAction(owner, run.id())).toList();
        if (pending.isEmpty()) return Optional.empty();
        if (pending.size() != 1) return Optional.of(ToolEvidence.finalVerified("agent.action", "มีหลายงานรอยืนยันครับ เลือกงานที่จะอนุมัติใน Cockpit"));
        var state = runtime.find(owner, pending.getFirst().id());
        try {
            runtime.decide(owner, state.runId(), state.digest(), approve);
            return Optional.of(ToolEvidence.finalVerified("agent.action", approve ? "อนุมัติขั้นที่เตรียมไว้แล้วครับ มินิคุงจะทำต่อและตรวจผล" : "ยกเลิกงานแล้วครับ"));
        } catch (RuntimeException e) { return Optional.of(ToolEvidence.finalFailed("agent.action", "ยังดำเนินการไม่ได้ครับ: " + e.getMessage())); }
    }
}
