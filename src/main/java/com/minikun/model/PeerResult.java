package com.minikun.model;

import java.util.Objects;

/** Typed collaborator output. Refusals and failures are never treated as facts. */
public record PeerResult(
        FriendCard friend,
        PeerStatus status,
        String content,
        String reason) {

    public PeerResult {
        friend = Objects.requireNonNull(friend, "friend must not be null");
        status = Objects.requireNonNull(status, "status must not be null");
        content = Objects.requireNonNullElse(content, "").strip();
        reason = Objects.requireNonNullElse(reason, "").strip();
    }

    public boolean usable() {
        return !content.isBlank() && (status == PeerStatus.COMPLETE || status == PeerStatus.PARTIAL);
    }

    public static PeerResult error(FriendCard friend, PeerStatus status, RuntimeException exception) {
        PeerStatus safeStatus = status == null ? PeerStatus.ERROR : status;
        String reason = exception == null ? "unknown" : exception.getClass().getSimpleName();
        return new PeerResult(friend, safeStatus, "", reason);
    }
}
