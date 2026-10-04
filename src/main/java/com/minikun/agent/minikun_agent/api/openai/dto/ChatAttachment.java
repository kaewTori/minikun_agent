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
		String license,
		String prompt,
		@JsonProperty("negative_prompt") String negativePrompt,
		Long seed,
		@JsonProperty("generation_id") String generationId,
		@JsonProperty("filename") String filename,
		@JsonProperty("content_type") String contentType,
		@JsonProperty("size_bytes") Long sizeBytes,
		@JsonProperty("slide_count") Integer slideCount,
		@JsonProperty("artifact_id") String artifactId) {
	public ChatAttachment(String type, String url, String title, String sourceUrl, String description,
			String origin, String originalUrl, String thumbnailUrl, Integer width, Integer height,
			String provider, String license, String prompt, String negativePrompt, Long seed, String generationId) {
		this(type, url, title, sourceUrl, description, origin, originalUrl, thumbnailUrl, width, height,
				provider, license, prompt, negativePrompt, seed, generationId, "", "", null, null, "");
	}

	public ChatAttachment(String type, String url, String title, String sourceUrl, String description,
			String origin, String originalUrl, String thumbnailUrl, Integer width, Integer height,
			String provider, String license) {
		this(type, url, title, sourceUrl, description, origin, originalUrl, thumbnailUrl,
				width, height, provider, license, "", "", null, "", "", "", null, null, "");
	}

	public ChatAttachment(String type, String url, String title) {
		this(type, url, title, "", "", "web", url, "", null, null, "", "", "", "", null, "",
				"", "", null, null, "");
	}
}
