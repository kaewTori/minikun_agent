package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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
                .map(image -> new ChatAttachment(
                        "image",
                        proxyUrl(image.url()),
                        image.title(),
                        image.sourceUrl(),
                        image.description(),
                        "web",
                        image.url(),
                        image.thumbnailUrl(),
                        image.width(),
                        image.height(),
                        image.provider(),
                        image.license()))
                .toList();
    }

    private static String proxyUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            return url;
        }
        return "/v1/images/proxy?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8);
    }
}
