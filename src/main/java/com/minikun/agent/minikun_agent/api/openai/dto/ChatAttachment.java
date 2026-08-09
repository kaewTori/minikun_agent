package com.minikun.agent.minikun_agent.api.openai.dto;

public record ChatAttachment(
		String type,
		String url,
		String title) {}