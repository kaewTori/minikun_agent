package com.minikun.tools;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.planner.PlannerConfirmationService;
import java.util.*;
import org.springframework.core.annotation.Order;

@Order(4)
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(PlannerConfirmationService.class)
public final class BrowserConfirmationRouter implements ToolRequestRouter {
    private final ToolExecutor executor;
    private final PlannerConfirmationService confirmations;
    public BrowserConfirmationRouter(ToolExecutor executor, PlannerConfirmationService confirmations) { this.executor = executor; this.confirmations = confirmations; }
    @Override public Optional<ToolEvidence> route(String text, ConversationId conversation) { return route(text, conversation, "default"); }
    @Override public Optional<ToolEvidence> route(String text, ConversationId conversation, String owner) {
        String value = Objects.toString(text, "").strip().toLowerCase(Locale.ROOT).replaceAll("[.!?]+$", "").replaceAll("(ครับ|ค่ะ|คะ)$", "").strip();
        if (!Set.of("ยืนยัน", "ตกลง", "ทำเลย", "confirm", "yes", "ok").contains(value)) return Optional.empty();
        var pending = confirmations.find(conversation, owner).filter(p -> p.action().equals("browser.execute"));
        if (pending.isEmpty()) return Optional.empty();
        String id = "browser-confirm-" + UUID.randomUUID();
        var result = executor.executeAuthorized(new ToolCallContext(conversation, id, owner), new ToolCall(id, "browser.control", confirmations.confirmedArguments(pending.get())));
        confirmations.clear(conversation);
        return Optional.of(result.success() ? ToolEvidence.verified("browser.control", "ดำเนินการกับเว็บแล้วครับ กรุณาตรวจสถานะหลังดำเนินการ\n" + result.value())
                : ToolEvidence.finalFailed("browser.control", "ดำเนินการไม่ได้ครับ หน้าหรือเป้าหมายอาจเปลี่ยน ต้องอ่านสถานะใหม่ก่อน"));
    }
}
