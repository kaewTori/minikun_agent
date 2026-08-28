package com.minikun.pcs.model;

public record ImageSource(
		String url,
		String title,
		String sourceUrl,
		String description,
		String thumbnailUrl,
		Integer width,
		Integer height,
		String provider,
		String license) {
	public ImageSource(String url, String title, String sourceUrl, String description) {
		this(url, title, sourceUrl, description, "", null, null, "", "");
	}
}
