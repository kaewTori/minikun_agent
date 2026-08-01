package com.minikun.memory;

import java.util.List;

import com.minikun.memory.model.CandidateMemory;
import com.minikun.memory.model.CompletedConversation;

public interface MemoryExtractionClient {
    List<CandidateMemory> extractConversationMemories(CompletedConversation conversation);
}
