package com.minikun.presentation;

import com.minikun.tools.Tool;
import com.minikun.tools.ToolCallContext;
import com.minikun.tools.ToolDefinition;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolResult;
import java.util.Map;
import java.util.Objects;

/** Lets Minikun retrieve the latest deck spec from this owner and conversation before revising it. */
final class PresentationReadTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "presentation.read_latest",
            "Read the latest PowerPoint previously created in this conversation, including its full editable slide spec. "
                    + "Use this before presentation.revise when the user asks to change a deck. It only returns a deck "
                    + "belonging to the current owner and conversation.", Map.of());
    private final PresentationService service;

    PresentationReadTool(PresentationService service) {
        this.service = Objects.requireNonNull(service, "presentation service must not be null");
    }

    @Override public ToolDefinition definition() { return DEFINITION; }
    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            PresentationStore.Stored deck = service.latest(context);
            return ToolResult.success(Map.of(
                    "artifact_id", deck.artifactId(),
                    "title", deck.title(),
                    "slide_count", deck.slideCount(),
                    "spec_json", deck.specJson()));
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "PowerPoint could not be read");
        }
    }
}
