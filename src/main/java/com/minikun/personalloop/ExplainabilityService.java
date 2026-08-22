package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.ExplainabilityTrace;

import com.minikun.agent.minikun_agent.api.openai.ChatExplainabilitySink;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Stores provenance and policy decisions, never prompts, model chain-of-thought, or retrieved content. */
public final class ExplainabilityService implements ChatExplainabilitySink {
    private final PersonalLoopStore store;
    private final PersonalTimelineRecorder timeline;
    private final Clock clock;

    public ExplainabilityService(PersonalLoopStore store, PersonalTimelineRecorder timeline, Clock clock) {
        this.store = Objects.requireNonNull(store); this.timeline = Objects.requireNonNull(timeline);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void record(Event event) {
        String summary = summary(event.sources(), event.tools(), event.decisions());
        ExplainabilityTrace trace = store.save(new ExplainabilityTrace(UUID.randomUUID(), event.ownerId(),
                event.conversationId(), event.responseId(), summary, event.sources(), event.tools(),
                event.decisions(), event.createdAt()));
        timeline.record(event.ownerId(), "RESPONSE_EXPLAINED", "EXPLAINABILITY", trace.responseId(),
                "บันทึกที่มาของคำตอบ", summary, Map.of("conversation_id", trace.conversationId()), event.createdAt());
    }

    public ExplainabilityTrace find(String ownerId, String responseId) {
        return store.trace(PersonalLoopModels.owner(ownerId), PersonalLoopModels.text(responseId, "response id"))
                .orElseThrow(() -> new IllegalArgumentException("explainability trace was not found"));
    }

    public List<ExplainabilityTrace> list(String ownerId, String conversationId, int limit) {
        if (limit < 1 || limit > 500) throw new IllegalArgumentException("limit must be between 1 and 500");
        return store.traces(PersonalLoopModels.owner(ownerId), conversationId, limit);
    }

    private String summary(List<String> sources, List<String> tools, Map<String, Object> decisions) {
        StringBuilder value = new StringBuilder("คำตอบนี้ใช้");
        value.append(sources.isEmpty() ? "ข้อมูลสนทนาและคำสั่งระบบ" : "แหล่งข้อมูล " + sources.size() + " รายการ");
        if (!tools.isEmpty()) value.append(" และเครื่องมือ ").append(String.join(", ", tools));
        if (Boolean.TRUE.equals(decisions.get("search_attempted"))) value.append(" โดยมีการค้นข้อมูลภายนอก");
        if (Boolean.TRUE.equals(decisions.get("confirmation_required"))) value.append(" และหยุดรอการยืนยันก่อนเปลี่ยนแปลงข้อมูล");
        return value.append("ครับ").toString();
    }
}
