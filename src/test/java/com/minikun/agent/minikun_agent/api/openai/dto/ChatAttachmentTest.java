package com.minikun.agent.minikun_agent.api.openai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatAttachmentTest {
    @Test
    void preservesFieldsAndUsesRecordEquality() {
        ChatAttachment attachment = new ChatAttachment("image", "https://example.com/image.jpg", "Example image");

        assertEquals("image", attachment.type());
        assertEquals("https://example.com/image.jpg", attachment.url());
        assertEquals("Example image", attachment.title());
        assertEquals(attachment, new ChatAttachment("image", "https://example.com/image.jpg", "Example image"));
    }
}