package com.minikun.presentation;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.tools.Tool;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolDefinition;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolParameter;
import com.minikun.tools.ToolParameterType;
import com.minikun.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Creates a PowerPoint from the slide content and visual direction authored by Minikun. */
final class PresentationTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(PresentationTool.class);
    private static final Map<String, Object> SPEC_SCHEMA = specSchema();
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "presentation.create",
            "Create the PowerPoint deck the user requested. You author the storyline, slide copy, "
                    + "sources, speaker notes, theme, and a distinct layout for each slide; this tool renders "
                    + "your choices into an editable .pptx. Use it only when the user wants an actual slide file, "
                    + "not when asking how to make one or discussing a plan. Pass spec as a nested JSON object, "
                    + "not as a quoted string. Its fields are "
                    + "{title,language,theme,slides:[{title,layout,body,bullets,leftTitle,leftBullets,rightTitle,"
                    + "rightBullets,value,valueLabel,quote,attribution,timeline:[{label,text}],imageUrl,"
                    + "speakerNotes,sources}]}. Theme is japanese, paper, ocean, or midnight. Layout is cover, "
                    + "editorial, split, comparison, cards, stat, quote, or timeline. Before writing slides, set an "
                    + "art direction for the audience and subject, then keep a coherent palette and type scale. "
                    + "Treat the art direction as guidance; include only fields listed in spec. Give every slide "
                    + "one message, a short "
                    + "headline (ideally under 8 words), and at most 3–5 concise points, ideally one short line each. "
                    + "Put supporting detail in speaker notes rather than shrinking slide text. Choose layouts to fit the story, vary composition "
                    + "across consecutive slides, and use whitespace deliberately. Use cards for grouped ideas, "
                    + "comparison for two sides, stat for one standout number, quote for a sourced voice, and timeline "
                    + "for ordered milestones. Use typography, spacing, color, and native editable shapes as real "
                    + "information graphics rather than decoration. For a slide that benefits from a specific "
                    + "illustration, call image.generate and use its local /v1/images/generated/<uuid>.(png|jpg) URL; "
                    + "use at most four distinct images, favor one purposeful hero image over generic stock-like art, "
                    + "and leave text out of generated images. Keep each slide focused. Respect the "
                    + "requested total including the cover; if the user gives no count, make 8 slides total. Choose a "
                    + "theme suited to the subject and maintain contrast. Include only real sources in speaker notes. "
                    + "If no image is needed, build visual interest with native editable cards, timelines, dividers, "
                    + "and scale changes; do not fall back to a wall of bullets.",
            Map.of("spec", new ToolParameter("spec", ToolParameterType.OBJECT, true,
                    "Nested object fields: title and theme strings, optional language string, and slides array. "
                            + "Each slide object supports title, layout, body, bullets, comparison fields, stat fields; "
                            + "a blank title is derived from body or bullets when possible. "
                            + "quote fields, timeline objects, imageUrl, speakerNotes, and sources as described above. "
                            + "Pass these fields directly; do not quote the object.", SPEC_SCHEMA)));

    private final PresentationService service;

    PresentationTool(PresentationService service) {
        this.service = Objects.requireNonNull(service, "presentation service must not be null");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            Object value = arguments == null ? null : arguments.get("spec");
            if (!(value instanceof Map<?, ?> spec)) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "spec must be a JSON object");
            }
            return createdResult(service.create(context, spec));
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("process=presentation_creation_exception cause={} message={}",
                    exception.getClass().getName(), exception.getMessage(), exception);
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "PowerPoint creation failed");
        }
    }

    static ToolResult createdResult(PresentationService.Created created) {
        PresentationStore.Stored file = created.presentation();
        ChatAttachment attachment = new ChatAttachment(
                "presentation", file.url(), file.title(), "", "ไฟล์ PowerPoint ที่มินิคุงสร้างให้",
                "generated", file.url(), "", null, null, "Apache POI", "",
                "", "", null, file.artifactId(), file.filename(), PresentationStore.CONTENT_TYPE,
                file.bytes(), file.slideCount(), file.artifactId());
        PresentationAttachmentScope.add(attachment);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifact_id", file.artifactId());
        result.put("filename", file.filename());
        result.put("title", file.title());
        result.put("url", file.url());
        result.put("content_type", PresentationStore.CONTENT_TYPE);
        result.put("bytes", file.bytes());
        result.put("slide_count", file.slideCount());
        result.put("theme", file.theme());
        result.put("parent_artifact_id", file.parentArtifactId());
        result.put("warnings", created.warnings());
        return ToolResult.success(Map.copyOf(result));
    }

    private static Map<String, Object> specSchema() {
        Map<String, Object> slideFields = new LinkedHashMap<>();
        slideFields.put("title", stringField());
        slideFields.put("layout", Map.of("type", "string", "description",
                "For comparison, also provide leftTitle and rightTitle. For stat, provide value; for quote, provide quote; for timeline, provide timeline points.", "enum",
                List.of("cover", "editorial", "split", "comparison", "cards", "stat", "quote", "timeline")));
        slideFields.put("body", stringField());
        slideFields.put("bullets", stringArray(5));
        slideFields.put("leftTitle", stringField());
        slideFields.put("leftBullets", stringArray(5));
        slideFields.put("rightTitle", stringField());
        slideFields.put("rightBullets", stringArray(5));
        slideFields.put("value", stringField());
        slideFields.put("valueLabel", stringField());
        slideFields.put("quote", stringField());
        slideFields.put("attribution", stringField());
        slideFields.put("timeline", Map.of("type", "array", "maxItems", 5, "items", Map.of(
                "type", "object", "properties", Map.of("label", stringField(), "text", stringField()),
                "required", List.of("label", "text"), "additionalProperties", false)));
        slideFields.put("imageUrl", stringField());
        slideFields.put("speakerNotes", stringField());
        slideFields.put("sources", stringArray(8));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", stringField());
        fields.put("language", stringField());
        fields.put("theme", Map.of("type", "string", "enum", List.of("japanese", "paper", "ocean", "midnight")));
        fields.put("slides", Map.of("type", "array", "minItems", 1, "maxItems", 20,
                "items", Map.of("type", "object", "properties", slideFields,
                        "required", List.of("title", "layout"), "additionalProperties", false)));
        return Map.of("properties", fields, "required", List.of("title", "theme", "slides"),
                "additionalProperties", false);
    }

    private static Map<String, Object> stringField() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> stringArray(int maximumItems) {
        return Map.of("type", "array", "maxItems", maximumItems, "items", stringField());
    }
}
