package com.minikun.tools;

import java.util.Map;
import java.util.Objects;

/** Server-owned, invocation-specific consent. Never constructed from model tool arguments. */
public final class ToolAuthorizationScope implements AutoCloseable {
    private static final ThreadLocal<ToolAuthorizationScope> CURRENT = new ThreadLocal<>();
    private final ToolAuthorizationScope previous;
    private final ToolCallContext context;
    private final String tool;
    private final Map<String, Object> arguments;

    public ToolAuthorizationScope(ToolCallContext context, ToolCall call) {
        previous = CURRENT.get();
        this.context = context;
        tool = call.name();
        arguments = normalized(call.arguments());
        CURRENT.set(this);
    }

    public static boolean permits(ToolCallContext context, String tool, Map<String, Object> arguments) {
        var value = CURRENT.get();
        return value != null && value.context.equals(context) && value.tool.equals(tool)
                && value.arguments.equals(normalized(arguments));
    }

    private static Map<String, Object> normalized(Map<String, Object> arguments) {
        var result = new java.util.LinkedHashMap<>(arguments);
        result.remove("confirmed");
        return Map.copyOf(result);
    }

    @Override public void close() {
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
    }
}
