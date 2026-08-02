package com.minikun.pcs.model;

public record KnowledgeContext(String content) {
	public static KnowledgeContext empty() {
		return new KnowledgeContext("");
	}
}