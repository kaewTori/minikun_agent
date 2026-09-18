package com.minikun.model;

import java.util.List;

/** Initial built-in cards; persistence and UI can be added when cards need editing. */
public final class FriendCards {
    public static final FriendCard CHATGPT = new FriendCard(
            "chatgpt", "ChatGPT", "primary external reasoning friend",
            "analysis, planning, explanation, code review",
            "may refuse or partially answer some requests", 1);
    public static final FriendCard KIMI_K3 = new FriendCard(
            "kimi-k3", "Kimi K3", "independent backup reasoning friend",
            "alternative reasoning and divergent viewpoints",
            "quality and availability depend on NVIDIA Build API", 2);
    public static final FriendCard CODEX = new FriendCard(
            "codex", "Codex", "repository and code execution specialist",
            "codebase work, diffs, tests, reviews",
            "should not be used as the normal chat answer writer", 3);

    private FriendCards() { }

    public static List<FriendCard> defaults() {
        return List.of(CHATGPT, KIMI_K3, CODEX);
    }

    public static FriendCard byId(String id) {
        return defaults().stream()
                .filter(card -> card.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown friend card: " + id));
    }
}
