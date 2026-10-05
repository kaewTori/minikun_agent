package com.minikun.voice;

import com.minikun.tools.*;
import java.util.Locale;
import java.util.Map;

/** Controls only Mini-kun's speech output; transcription stays available. */
public final class VoiceRuntimeTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "voice.tts",
            "Inspect or disable/enable Mini-kun's local TTS. disable releases the model process and blocks "
                    + "automatic restart; enable allows speech on the next request. STT is unaffected. "
                    + "For changes ask the user to send an explicit command such as ปิด TTS or เปิด TTS.",
            Map.of("action", new ToolParameter("action", ToolParameterType.STRING, true,
                            "status, disable, or enable"),
                    "confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false,
                            "Server-owned authorization; model arguments cannot grant permission.")));
    private final VoiceService voice;

    public VoiceRuntimeTool(VoiceService voice) { this.voice = java.util.Objects.requireNonNull(voice); }

    @Override public ToolDefinition definition() { return DEFINITION; }

    @Override public boolean requiresExplicitConfirmation(Map<String, Object> arguments) {
        return !"status".equalsIgnoreCase(String.valueOf(arguments.get("action")));
    }

    @Override public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        String action = String.valueOf(arguments.get("action")).trim().toLowerCase(Locale.ROOT);
        if (!java.util.Set.of("status", "disable", "enable").contains(action)) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "action must be status, disable, or enable");
        }
        if (!"status".equals(action) && !ToolAuthorizationScope.permits(context, DEFINITION.name(), arguments)) {
            return ToolResult.failure(ToolErrorCode.REVIEW_REQUIRED, "ส่งคำสั่ง ปิด TTS หรือ เปิด TTS เพื่อเปลี่ยนสถานะครับ");
        }
        try {
            return ToolResult.success("status".equals(action) ? voice.synthesisStatus()
                    : voice.setSynthesisEnabled("enable".equals(action)));
        } catch (VoiceException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, exception.getMessage());
        }
    }
}
