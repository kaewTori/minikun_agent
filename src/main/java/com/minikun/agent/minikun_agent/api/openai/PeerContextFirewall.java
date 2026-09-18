package com.minikun.agent.minikun_agent.api.openai;

import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/** Small outbound filter for collaborator prompts. It deliberately omits internal system instructions. */
final class PeerContextFirewall {
    private static final int MAX_CHARACTERS = 30_000;
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)(api[-_ ]?key|access[-_ ]?token|password|secret|authorization)\\s*[:=]\\s*[^\\s,;]+")
            ;
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+\\b");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]{12,}\\b");
    private static final Pattern NVIDIA_KEY = Pattern.compile("\\bnvapi-[A-Za-z0-9_-]{12,}\\b");

    private PeerContextFirewall() { }

    static String render(Prompt prompt) {
        if (prompt == null) return "";
        StringBuilder rendered = new StringBuilder();
        for (Message message : prompt.getInstructions()) {
            if (message == null || message.getMessageType() == null || "system".equalsIgnoreCase(
                    message.getMessageType().getValue())) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) continue;
            rendered.append('[').append(message.getMessageType().getValue()).append("]\n")
                    .append(sanitize(text)).append("\n\n");
        }
        return bound(rendered.toString().strip());
    }

    static String sanitize(String value) {
        if (value == null || value.isBlank()) return "";
        return NVIDIA_KEY.matcher(OPENAI_KEY.matcher(BEARER.matcher(KEY_VALUE.matcher(value)
                        .replaceAll("$1=[REDACTED]")).replaceAll("Bearer [REDACTED]"))
                .replaceAll("[REDACTED]")).replaceAll("[REDACTED]");
    }

    private static String bound(String value) {
        if (value.length() <= MAX_CHARACTERS) return value;
        return value.substring(0, MAX_CHARACTERS).stripTrailing()
                + "\n[external context truncated by application]";
    }
}
