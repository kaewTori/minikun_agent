package com.minikun.agent.minikun_agent.api.openai.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/** OpenAI-compatible message supporting both legacy text and multimodal content parts. */
public final class Message {
    private final String role;
    private final String content;
    private final List<ContentPart> contentParts;

    public Message(String role, String content) {
        this(role, content, List.of());
    }

    private Message(String role, String content, List<ContentPart> contentParts) {
        this.role = role;
        this.content = content;
        this.contentParts = List.copyOf(contentParts == null ? List.of() : contentParts);
    }

    @JsonCreator
    public static Message fromJson(
            @JsonProperty("role") String role,
            @JsonProperty("content") Object rawContent) {
        if (rawContent == null || rawContent instanceof String) {
            return new Message(role, (String) rawContent);
        }
        if (!(rawContent instanceof List<?> rawParts)) {
            throw new IllegalArgumentException("message content must be a string or an array of content parts");
        }

        List<ContentPart> parts = new ArrayList<>();
        List<String> textParts = new ArrayList<>();
        for (Object rawPart : rawParts) {
            if (!(rawPart instanceof Map<?, ?> node)) {
                throw new IllegalArgumentException("each message content part must be an object");
            }
            String type = text(node, "type");
            String text = text(node, "text");
            Object rawImage = node.get("image_url");
            Map<?, ?> imageNode = rawImage instanceof Map<?, ?> map ? map : null;
            ImageUrl imageUrl = imageNode == null
                    ? null
                    : new ImageUrl(text(imageNode, "url"), text(imageNode, "detail"));
            if (!"text".equals(type) && !"image_url".equals(type)) {
                throw new IllegalArgumentException("unsupported message content part type: " + type);
            }
            if ("text".equals(type) && text == null) {
                throw new IllegalArgumentException("text content parts must contain text");
            }
            if ("image_url".equals(type) && (imageUrl == null || imageUrl.url() == null)) {
                throw new IllegalArgumentException("image_url content parts must contain a URL");
            }
            ContentPart part = new ContentPart(type, text, imageUrl);
            parts.add(part);
            if ("text".equals(type) && text != null && !text.isBlank()) {
                textParts.add(text);
            }
        }
        return new Message(role, String.join("\n", textParts), parts);
    }

    private static String text(Map<?, ?> node, String field) {
        Object value = node == null ? null : node.get(field);
        return value == null ? null : String.valueOf(value);
    }

    @JsonProperty("role")
    public String role() {
        return role;
    }

    @JsonIgnore
    public String content() {
        return content;
    }

    @JsonIgnore
    public List<ContentPart> contentParts() {
        return contentParts;
    }

    @JsonIgnore
    public boolean hasImageContent() {
        return contentParts.stream().anyMatch(part -> "image_url".equals(part.type()));
    }

    @JsonProperty("content")
    public Object jsonContent() {
        return contentParts.isEmpty() ? content : contentParts;
    }

    public record ContentPart(
            String type,
            String text,
            @JsonProperty("image_url") ImageUrl imageUrl) {
    }

    public record ImageUrl(String url, String detail) {
    }
}
