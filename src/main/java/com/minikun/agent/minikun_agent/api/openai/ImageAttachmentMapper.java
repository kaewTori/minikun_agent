package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.pcs.model.ImageSource;

final class ImageAttachmentMapper {
    private ImageAttachmentMapper() {
    }

    static List<ChatAttachment> map(List<ImageSource> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        return images.stream()
                .map(image -> new ChatAttachment("image", image.url(), image.title()))
                .toList();
    }
}