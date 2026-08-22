package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.ToolEvidence;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Converts visible chat provenance into a bounded audit event. */
@Slf4j
final class ChatExplainabilityRecorder {
    private final ChatExplainabilitySink sink;

    ChatExplainabilityRecorder(ChatExplainabilitySink sink) { this.sink = sink; }

    Context context(ChatKnowledgeSelection knowledge, ToolEvidence tool, String generationProfile) {
        List<String> sources = knowledge.selection().selectedCandidates().stream()
                .map(candidate -> {
                    String provenance = candidate.provenance().isBlank()
                            ? candidate.candidateId() : candidate.provenance();
                    String bounded = provenance.length() <= 300 ? provenance : provenance.substring(0, 300);
                    return candidate.source().name() + ":" + bounded;
                }).distinct().limit(20).toList();
        Map<String, Object> decisions = new LinkedHashMap<>();
        decisions.put("search_requested", knowledge.searchContext().searchRequested());
        decisions.put("search_attempted", knowledge.searchContext().searchAttempted());
        decisions.put("search_knowledge_available", knowledge.searchContext().searchKnowledgeAvailable());
        decisions.put("ranking_fallback", knowledge.selection().rankingFallback());
        decisions.put("research_autonomous", knowledge.researchTrace().autonomous());
        decisions.put("generation_profile", generationProfile == null ? "general" : generationProfile);
        decisions.put("confirmation_required", tool != null && tool.requiresConfirmation());
        return new Context(sources, tool == null ? List.of() : List.of(tool.toolName()), decisions);
    }

    void record(String ownerId, ConversationId conversationId, String responseId, Context context) {
        if (sink == null || ownerId == null || conversationId == null || context == null) return;
        try {
            sink.record(new ChatExplainabilitySink.Event(ownerId, conversationId.value(), responseId,
                    context.sources(), context.tools(), context.decisions(), Instant.now()));
        } catch (RuntimeException exception) {
            log.warn("process=explainability event=record_failed request_id={} reason={}",
                    responseId, exception.getMessage());
        }
    }

    static Context forTool(ToolEvidence evidence) {
        return new Context(List.of(), List.of(evidence.toolName()),
                Map.of("deterministic_tool_response", true,
                        "confirmation_required", evidence.requiresConfirmation()));
    }

    static Context browserFailure() {
        return new Context(List.of(), List.of("web.open_url"),
                Map.of("browser_failed", true, "search_attempted", false,
                        "confirmation_required", false));
    }

    record Context(List<String> sources, List<String> tools, Map<String, Object> decisions) {
        Context {
            sources = sources == null ? List.of() : List.copyOf(sources);
            tools = tools == null ? List.of() : List.copyOf(tools);
            decisions = decisions == null ? Map.of() : Map.copyOf(decisions);
        }
    }
}
