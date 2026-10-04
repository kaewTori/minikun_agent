package com.minikun.presentation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.tools.ToolCallContext;
import com.minikun.visual.GeneratedImageStore;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

final class PresentationService {
    private final ObjectMapper mapper;
    private final PresentationRenderer renderer;
    private final PresentationStore store;

    PresentationService(ObjectMapper mapper, GeneratedImageStore images, PresentationStore store) {
        this.mapper = Objects.requireNonNull(mapper, "object mapper must not be null");
        this.renderer = new PresentationRenderer(images);
        this.store = Objects.requireNonNull(store, "presentation store must not be null");
    }

    Created create(ToolCallContext context, String json) {
        return create(context, json, "");
    }

    Created create(ToolCallContext context, Map<?, ?> values) {
        if (values == null) throw new IllegalArgumentException("spec must be a JSON object");
        PresentationSpec spec;
        try {
            spec = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .convertValue(values, PresentationSpec.class);
        } catch (IllegalArgumentException exception) {
            Throwable cause = exception.getCause();
            String detail = cause == null ? exception.getMessage() : cause.getMessage();
            if (cause instanceof JsonMappingException mapping && !mapping.getPathReference().isBlank()) {
                detail += " at " + mapping.getPathReference();
            }
            throw new IllegalArgumentException("spec must match the documented slide schema: " + detail, exception);
        }
        return renderAndStore(context, spec, "");
    }

    Created revise(ToolCallContext context, String sourceArtifactId, String json) {
        PresentationStore.Stored source = store.read(sourceArtifactId, context.ownerId());
        if (!source.conversationId().equals(context.conversationId().value())) {
            throw new IllegalArgumentException("presentation was not found");
        }
        return create(context, json, source.artifactId());
    }

    PresentationStore.Stored latest(ToolCallContext context) {
        return store.latest(context.ownerId(), context.conversationId().value());
    }

    private Created create(ToolCallContext context, String json, String parentArtifactId) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > 128_000
                || json.length() > PresentationSpec.MAX_SPEC_CHARACTERS) {
            throw new IllegalArgumentException("presentation instructions exceed the size limit");
        }
        PresentationSpec spec;
        try {
            spec = mapper.readerFor(PresentationSpec.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(json);
        } catch (JsonProcessingException exception) {
            var location = exception.getLocation();
            throw new IllegalArgumentException("spec_json is invalid: " + exception.getOriginalMessage()
                    + (location == null ? "" : " at line " + location.getLineNr() + ", column "
                            + location.getColumnNr()), exception);
        }
        return renderAndStore(context, spec, parentArtifactId);
    }

    private Created renderAndStore(ToolCallContext context, PresentationSpec spec, String parentArtifactId) {
        var rendered = renderer.render(spec);
        var stored = store.save(rendered.bytes(), spec, context.ownerId(),
                context.conversationId().value(), context.requestId(), parentArtifactId);
        return new Created(stored, rendered.warnings());
    }

    PresentationStore.Stored read(String artifactId, String ownerId) {
        return store.read(artifactId, ownerId);
    }

    byte[] bytes(PresentationStore.Stored presentation) {
        return store.bytes(presentation);
    }

    record Created(PresentationStore.Stored presentation, java.util.List<String> warnings) { }
}
