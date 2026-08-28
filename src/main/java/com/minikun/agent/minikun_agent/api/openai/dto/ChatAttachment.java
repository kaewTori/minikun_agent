package com.minikun.agent.minikun_agent.api.openai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatAttachment(
		String type,
		String url,
		String title,
		@JsonProperty("source_url") String sourceUrl,
		String description,
		String origin,
		@JsonProperty("original_url") String originalUrl,
		@JsonProperty("thumbnail_url") String thumbnailUrl,
		Integer width,
		Integer height,
		String provider,
		String license) {
	public ChatAttachment(String type, String url, String title) {
		this(type, url, title, "", "", "web", url, "", null, null, "", "");
	}
}
