package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import java.util.List;
import org.junit.jupiter.api.Test;

class PresentationChatGuardTest {
    @Test
    void usesTheRealAttachmentForDeliveryInsteadOfModelInventedLinksOrClaims() {
        var guard = new PresentationChatGuard();
        var attachment = mock(ChatAttachment.class);
        when(attachment.type()).thenReturn("presentation");
        String response = guard.responseContent("ดาวน์โหลดที่ https://example.com/v1/presentations/fake/download",
                List.of(attachment));
        assertFalse(response.contains("example.com"));
        assertEquals(guard.responseContent("สร้างไม่สำเร็จ", List.of(attachment)), response);
        assertEquals(guard.failureNotice(), guard.responseContent("สร้างและแนบไฟล์แล้ว", List.of()));
    }
}
