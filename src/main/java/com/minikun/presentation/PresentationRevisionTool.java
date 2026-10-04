package com.minikun.presentation;

import com.minikun.tools.Tool;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolDefinition;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolParameter;
import com.minikun.tools.ToolParameterType;
import com.minikun.tools.ToolResult;
import java.util.Map;
import java.util.Objects;

/** Renders a revised spec as a new artifact while keeping its source deck intact. */
final class PresentationRevisionTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "presentation.revise",
            "Create a new PowerPoint revision without overwriting its source. First call presentation.read_latest to "
                    + "get the current spec and artifact_id, then apply the user's requested changes and pass the full "
                    + "updated spec as spec_json together with that artifact_id. Keep unrelated slide content and the "
                    + "requested slide count unchanged. Use the same theme and layouts unless the user requests a change.",
            Map.of(
                    "artifact_id", new ToolParameter("artifact_id", ToolParameterType.STRING, true,
                            "The artifact_id returned by presentation.read_latest."),
                    "spec_json", new ToolParameter("spec_json", ToolParameterType.STRING, true,
                            "The full updated editable slide deck spec.")));
    private final PresentationService service;

    PresentationRevisionTool(PresentationService service) {
        this.service = Objects.requireNonNull(service, "presentation service must not be null");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            Object artifactId = arguments == null ? null : arguments.get("artifact_id");
            Object specJson = arguments == null ? null : arguments.get("spec_json");
            if (!(artifactId instanceof String source) || !(specJson instanceof String json)) {
                return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS,
                        "artifact_id and spec_json must be strings");
            }
            return PresentationTool.createdResult(service.revise(context, source, json));
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "PowerPoint revision failed");
        }
    }
}
