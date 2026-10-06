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
                    + "Treat the art direction as guidance; include only fields listed in spec. Match the user's "
                    + "language, audience, objective, and supplied outline; preserve every requested topic. "
                    + "Write substantive explanations: each main point should state what happens, why it matters, "
                    + "or how to apply it. Explain capabilities through a concrete user action and required input "
                    + "or context, rather than topic labels or generic benefits. Do not assume automatic access to "
                    + "every workspace file; describe which relevant context must be selected or attached. "
                    + "Use natural language appropriate to the audience. Explain unfamiliar terms at first use. "
                    + "Write complete short sentences naming the action and its result; do not concatenate labels "
                    + "or use vague instructions such as 'use the Test function'. For example, say 'Ask for unit "
                    + "tests for the normal and boundary cases, then run them to check that behavior is unchanged'. "
                    + "For Thai copy, prefer sentences such as 'สั่งให้เขียนเทสต์สำหรับกรณีปกติและกรณีขอบเขต "
                    + "แล้วรันเพื่อเช็กว่าผลลัพธ์ยังเหมือนเดิม' over 'ใช้ฟังก์ชัน Test'. "
                    + "Do not repeat the slide title in the body. Each sentence must add a distinct useful point. "
                    + "Remove introductory labels or summaries that repeat the following explanation. "
                    + "Separate independent paragraphs with a blank line. Use single newlines within code blocks "
                    + "so code lines keep their indentation without extra paragraph gaps. "
                    + "Include a concrete worked example for instructional decks, with real input, "
                    + "an expected result, and the relevant limitation. Avoid slogans and generic benefits. "
                    + "Give every slide one message and a short headline. Preserve every requested condition and "
                    + "limitation in visible content; naming a topic does not explain it. Avoid filler to meet a point count. "
                    + "The renderer uses 44pt cover titles, 34pt slide titles, and at least 24pt body text. "
                    + "Body text has generous line and paragraph spacing. Budget at most six displayed lines per "
                    + "slide and two points per side for narrow layouts; shorten wording rather than compress spacing. "
                    + "Put extended explanation in speakerNotes, but keep the actual example and key evidence visible "
                    + "in body or bullets. Never leave an example slide empty or write 'add a screenshot here'. "
                    + "Use comparison only with substantive leftBullets and rightBullets; general bullets use editorial. "
                    + "Use only content fields rendered by that layout; do not put code or results in unused fields. "
                    + "Write plain text, with real newlines for code, and no HTML heading tags or markup fragments. "
                    + "Before creating, check that the deck answers the request, covers the supplied outline, and contains "
                    + "no empty content slides or invented facts. Choose layouts to fit the story, vary composition "
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
        slideFields.put("body", Map.of("type", "string", "minLength", 1));
        slideFields.put("bullets", stringArray(5));
        slideFields.put("leftTitle", stringField());
        slideFields.put("leftBullets", stringArray(5));
        slideFields.put("rightTitle", stringField());
        slideFields.put("rightBullets", stringArray(5));
        slideFields.put("value", Map.of("type", "string", "minLength", 1, "maxLength", 16,
                "description", "One short statistic or number; never put source code here."));
        slideFields.put("valueLabel", stringField());
        slideFields.put("quote", stringField());
        slideFields.put("attribution", stringField());
        slideFields.put("timeline", Map.of("type", "array", "minItems", 1, "maxItems", 5, "items", Map.of(
                "type", "object", "properties", Map.of("label", stringField(), "text", stringField()),
                "required", List.of("label", "text"), "additionalProperties", false)));
        slideFields.put("imageUrl", stringField());
        slideFields.put("speakerNotes", stringField());
        slideFields.put("sources", stringArray(8));

        var variants = new java.util.ArrayList<Map<String, Object>>();
        for (String layout : List.of("cover", "editorial", "split", "comparison", "cards", "stat", "quote", "timeline")) {
            List<String> contentFields = switch (layout) {
                case "cover" -> List.of("body", "imageUrl");
                case "editorial", "split" -> List.of("body", "bullets", "imageUrl");
                case "cards" -> List.of("body", "bullets");
                case "comparison" -> List.of("leftTitle", "leftBullets", "rightTitle", "rightBullets");
                case "stat" -> List.of("value", "valueLabel", "body");
                case "quote" -> List.of("quote", "attribution");
                default -> List.of("timeline");
            };
            Map<String, Object> properties = new LinkedHashMap<>();
            for (String field : List.of("title", "speakerNotes", "sources")) properties.put(field, slideFields.get(field));
            properties.put("layout", Map.of("type", "string", "enum", List.of(layout)));
            contentFields.forEach(field -> properties.put(field, slideFields.get(field)));
            var required = new java.util.ArrayList<>(List.of("title", "layout"));
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("type", "object");
            variant.put("properties", properties);
            variant.put("additionalProperties", false);
            switch (layout) {
                case "comparison" -> required.addAll(contentFields);
                case "cards" -> required.add("bullets");
                case "stat" -> required.add("value");
                case "quote" -> required.add("quote");
                case "timeline" -> required.add("timeline");
                case "editorial", "split" -> required.add("body");
                default -> { }
            }
            variant.put("required", required);
            variants.add(variant);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", stringField());
        fields.put("language", stringField());
        fields.put("theme", Map.of("type", "string", "enum", List.of("japanese", "paper", "ocean", "midnight")));
        fields.put("slides", Map.of("type", "array", "minItems", 1, "maxItems", 20,
                "items", Map.of("oneOf", variants)));
        return Map.of("properties", fields, "required", List.of("title", "theme", "slides"),
                "additionalProperties", false);
    }

    private static Map<String, Object> stringField() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> stringArray(int maximumItems) {
        return Map.of("type", "array", "minItems", 1, "maxItems", maximumItems, "items", stringField());
    }
}
