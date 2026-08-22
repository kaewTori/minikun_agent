package com.minikun.agent.minikun_agent.api.openai;

import java.util.Map;
import org.slf4j.MDC;

final class ChatTraceScope {
    private ChatTraceScope() { }

    static void run(String traceId, Runnable action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (traceId == null || traceId.isBlank() || "-".equals(traceId)) MDC.remove("trace_id");
            else MDC.put("trace_id", traceId);
            action.run();
        } finally {
            try {
                if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
            } catch (RuntimeException ignored) { }
        }
    }
}
