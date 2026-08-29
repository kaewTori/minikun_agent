package com.minikun.conversation.repair;

import java.util.Objects;

public record ConversationRepairAdvice(boolean required, Reason reason, String instruction) {
    public static final ConversationRepairAdvice NONE = new ConversationRepairAdvice(false, Reason.NONE, "");

    public ConversationRepairAdvice {
        reason = Objects.requireNonNull(reason, "reason must not be null");
        instruction = Objects.requireNonNullElse(instruction, "").strip();
    }

    public enum Reason {
        NONE,
        SUBJECT_MISMATCH,
        VISUAL_RESULT_MISSING,
        FOLLOW_UP_GUARD
    }
}
