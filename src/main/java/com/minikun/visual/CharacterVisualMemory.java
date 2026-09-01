package com.minikun.visual;

import java.util.List;

/** Local visual-profile persistence scoped to one owner and conversation. */
public interface CharacterVisualMemory {
    List<CharacterVisualProfile> find(String ownerId, String conversationId);

    void save(String ownerId, String conversationId, List<CharacterVisualProfile> profiles);
}
