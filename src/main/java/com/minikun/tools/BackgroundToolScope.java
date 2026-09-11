package com.minikun.tools;

import java.util.UUID;

/** Bound to the synchronous background chat worker, including deterministic and model tool calls. */
public final class BackgroundToolScope implements AutoCloseable {
    private static final ThreadLocal<BackgroundToolScope> CURRENT = new ThreadLocal<>();
    private final BackgroundToolScope previous;
    private final boolean replay;
    private final UUID operationId;
    private final java.util.function.BooleanSupplier active;
    private boolean blocked;

    public BackgroundToolScope(UUID operationId, boolean replay) {
        this(operationId, replay, () -> true);
    }

    public BackgroundToolScope(UUID operationId, boolean replay, java.util.function.BooleanSupplier active) {
        this.previous = CURRENT.get();
        this.operationId = operationId;
        this.replay = replay;
        this.active = active;
        CURRENT.set(this);
    }

    public static ToolResult guard(boolean changesState) {
        BackgroundToolScope scope = CURRENT.get();
        if (Thread.currentThread().isInterrupted()
                || scope != null && (!scope.active.getAsBoolean() || scope.replay && changesState)) {
            if (scope != null) scope.blocked = true;
            return ToolResult.failure(ToolErrorCode.REVIEW_REQUIRED,
                    "Operation interrupted or replayed: inspect existing results before applying a write. operation_id="
                            + (scope == null ? "foreground" : scope.operationId));
        }
        return null;
    }

    public boolean blocked() { return blocked; }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
