package com.minikun.communication;

import java.util.List;

public record CommunicationResult(
        CommunicationAction action,
        String channel,
        String language,
        String text,
        boolean draftOnly,
        boolean sendSupported,
        List<String> warnings) {

    public CommunicationResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
