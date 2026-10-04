package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.tools.springai.SpringAiToolCallingRuntime;
import java.util.List;

/** Keeps presentation delivery claims aligned with the attachment actually returned to chat. */
final class PresentationChatGuard {
    private static final String FAILURE_NOTICE =
            "ขออภัยครับ รอบนี้ยังสร้างไฟล์ PowerPoint ไม่สำเร็จ จึงไม่มีไฟล์แนบให้ดาวน์โหลดครับ ลองขอให้สร้างอีกครั้งได้เลยนะครับ";
    private static final String SUCCESS_NOTICE =
            "สร้างและแนบไฟล์ PowerPoint ให้แล้วครับ ดาวน์โหลดได้จากการ์ดไฟล์ในแชตนี้ครับ";
    private final ToolRuntimeIntentDetector intentDetector = new ToolRuntimeIntentDetector();

    boolean requested(ChatMessage userMessage) {
        return userMessage != null && intentDetector.requestsPresentationDeliverable(userMessage.content());
    }

    boolean available(boolean toolsEnabled, SpringAiToolCallingRuntime runtime) {
        return toolsEnabled && runtime != null;
    }

    String failureNotice() {
        return FAILURE_NOTICE;
    }

    String responseContent(String content, List<ChatAttachment> attachments) {
        boolean attached = attachments != null && attachments.stream().anyMatch(attachment ->
                attachment != null && "presentation".equals(attachment.type()));
        if (!attached) return FAILURE_NOTICE;
        if (content == null || content.isBlank() || content.contains("ไม่พบไฟล์") || content.contains("ไม่มีไฟล์")
                || content.contains("ไม่มีลิงก์") || content.contains("ไม่มีพาธ")
                || content.contains("ไม่สามารถดาวน์โหลด")) {
            return SUCCESS_NOTICE;
        }
        return content;
    }
}
