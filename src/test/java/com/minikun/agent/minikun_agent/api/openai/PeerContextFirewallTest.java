package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class PeerContextFirewallTest {
    @Test
    void omitsInternalSystemPromptAndRedactsCredentials() {
        String rendered = PeerContextFirewall.render(new Prompt(List.of(
                new SystemMessage("internal Minikun policy"),
                new UserMessage("ช่วยวิเคราะห์ api_key=sk-test-secret-123456 และ Bearer abc.def"))));

        assertFalse(rendered.contains("internal Minikun policy"));
        assertFalse(rendered.contains("sk-test-secret-123456"));
        assertFalse(rendered.contains("Bearer abc.def"));
        assertTrue(rendered.contains("ช่วยวิเคราะห์"));
    }
}
