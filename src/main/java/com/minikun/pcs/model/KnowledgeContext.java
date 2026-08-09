package com.minikun.pcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.minikun.pcs.KnowledgeCandidate;

import java.util.List;
import java.util.Objects;

public record KnowledgeContext(
		String content,
		@JsonIgnore List<KnowledgeCandidate> candidates,
		List<ImageSource> images) {
	public KnowledgeContext(String content) {
		this(content, List.of(), List.of());
	}

	public KnowledgeContext(String content, List<KnowledgeCandidate> candidates) {
		this(content, candidates, List.of());
	}

	public KnowledgeContext {
		content = Objects.requireNonNullElse(content, "");
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
		images = images == null ? List.of() : List.copyOf(images);
	}

	public static KnowledgeContext empty() {
		return new KnowledgeContext("");
	}

	public static KnowledgeContext fromCandidates(List<KnowledgeCandidate> candidates) {
		List<KnowledgeCandidate> snapshot = candidates == null ? List.of() : List.copyOf(candidates);
		String content = snapshot.stream()
				.map(KnowledgeCandidate::content)
				.reduce((left, right) -> left + "\n" + right)
				.orElse("");
		return new KnowledgeContext(content, snapshot, List.of());
	}
}