package com.minikun.voice;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.*;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Only complete, explicit TTS commands authorize a state change. */
public final class VoiceRuntimeRouter implements ToolRequestRouter {
    private static final Pattern COMMAND = Pattern.compile(
            "(?iu)^\\s*(?:(?:มินิคุง|mini-kun)[,\\s]*)?(?:(?:ช่วย|กรุณา)\\s*)?"
                    + "(ปิด|หยุด|เปิด|ดูสถานะ|ตรวจสถานะ|เช็กสถานะ|สถานะ|disable|stop|turn off|enable|start|turn on|status)"
                    + "\\s*(?:ระบบ\\s*)?(?:tts|text[- ]to[- ]speech|เสียงพูด|ระบบพูด)"
                    + "\\s*(?:(?:ให้หน่อย|หน่อย|ด้วย|ที|ให้ที|please)\\s*)?(?:ครับ|ค่ะ|นะ|นะครับ|นะคะ)?[.!?]*\\s*$");
    private final ToolExecutor executor;

    public VoiceRuntimeRouter(ToolExecutor executor) { this.executor = java.util.Objects.requireNonNull(executor); }

    @Override public Optional<ToolEvidence> route(String text, ConversationId conversationId) {
        return route(text, conversationId, "default");
    }

    @Override public Optional<ToolEvidence> route(String text, ConversationId conversationId, String ownerId) {
        if (text == null || conversationId == null) return Optional.empty();
        var match = COMMAND.matcher(text);
        if (!match.matches()) return Optional.empty();
        String action = switch (match.group(1).toLowerCase(java.util.Locale.ROOT)) {
            case "ปิด", "หยุด", "disable", "stop", "turn off" -> "disable";
            case "เปิด", "enable", "start", "turn on" -> "enable";
            default -> "status";
        };
        String id = "tts-route-" + UUID.randomUUID();
        var context = new ToolCallContext(conversationId, id, ownerId);
        var call = new ToolCall(id, "voice.tts", Map.of("action", action));
        ToolResult result = "status".equals(action) ? executor.execute(context, call)
                : executor.executeAuthorized(context, call);
        if (!result.success()) return Optional.of(ToolEvidence.finalFailed("voice.tts",
                "เปลี่ยนหรืออ่านสถานะ TTS ไม่สำเร็จ: " + result.error()));
        var values = (Map<?, ?>) result.value();
        String message = switch (action) {
            case "disable" -> "ปิด TTS แล้วครับ คืนหน่วยความจำของ process ที่มินิคุงดูแลแล้ว "
                    + "และจะไม่เปิดกลับเองจนกว่าจะสั่ง เปิด TTS (การถอดเสียงยังใช้ได้)";
            case "enable" -> "เปิดใช้งาน TTS แล้วครับ จะโหลดโมเดลเมื่อมีคำขอเสียงครั้งถัดไป";
            default -> "TTS: " + (Boolean.TRUE.equals(values.get("enabled")) ? "เปิดใช้งาน" : "ปิดอยู่")
                    + "; process: " + (Boolean.TRUE.equals(values.get("running")) ? "กำลังรัน" : "ไม่ได้รัน")
                    + "; provider: " + values.get("provider");
        };
        return Optional.of(ToolEvidence.finalVerified("voice.tts", message));
    }
}
