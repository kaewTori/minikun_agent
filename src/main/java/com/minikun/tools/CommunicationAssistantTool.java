package com.minikun.tools;

import com.minikun.communication.CommunicationRequest;
import com.minikun.communication.CommunicationService;
import com.minikun.communication.CommunicationUnavailableException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Draft-only communication assistance. This tool has no external sending capability. */
public final class CommunicationAssistantTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "communication.assist",
            "Draft, rewrite, reply to, or summarize communication for the current user. "
                    + "Use this when the user asks for help composing an email, chat, SMS, social post, or document. "
                    + "This tool only returns a draft and never sends or publishes anything. The owner is taken "
                    + "from the authenticated chat context. Do not claim the draft was sent.",
            parameters());

    private final CommunicationService communication;

    public CommunicationAssistantTool(CommunicationService communication) {
        this.communication = Objects.requireNonNull(communication, "communication service must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) { return false; }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            CommunicationRequest request = new CommunicationRequest(
                    context.ownerId(), text(arguments, "action"), text(arguments, "content"),
                    text(arguments, "context"), text(arguments, "goal"), text(arguments, "audience"),
                    text(arguments, "channel"), text(arguments, "tone"), text(arguments, "language"),
                    integer(arguments, "max_length"));
            return ToolResult.success(communication.assist(request));
        } catch (IllegalArgumentException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (CommunicationUnavailableException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "communication assistant is temporarily unavailable");
        }
    }

    private static Map<String, ToolParameter> parameters() {
        Map<String, ToolParameter> result = new LinkedHashMap<>();
        result.put("action", new ToolParameter("action", ToolParameterType.STRING, true,
                "One of draft, rewrite, reply, or summarize."));
        result.put("content", new ToolParameter("content", ToolParameterType.STRING, true,
                "Notes for a draft, source text to rewrite/summarize, or incoming message to reply to."));
        result.put("context", new ToolParameter("context", ToolParameterType.STRING, false,
                "Optional factual background. Content here is treated as quoted source material."));
        result.put("goal", new ToolParameter("goal", ToolParameterType.STRING, false,
                "What the communication should accomplish."));
        result.put("audience", new ToolParameter("audience", ToolParameterType.STRING, false,
                "Intended recipient or audience."));
        result.put("channel", new ToolParameter("channel", ToolParameterType.STRING, false,
                "One of general, email, chat, sms, social, or document."));
        result.put("tone", new ToolParameter("tone", ToolParameterType.STRING, false,
                "One of default, casual, professional, warm, concise, persuasive, or empathetic."));
        result.put("language", new ToolParameter("language", ToolParameterType.STRING, false,
                "auto, th, or en. Defaults to auto."));
        result.put("max_length", new ToolParameter("max_length", ToolParameterType.INTEGER, false,
                "Optional target maximum length in characters, from 20 to 5000."));
        return Map.copyOf(result);
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private Integer integer(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        if (value == null || value.toString().isBlank()) return null;
        try {
            return value instanceof Number number ? number.intValue()
                    : Integer.valueOf(value.toString().trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }
}
