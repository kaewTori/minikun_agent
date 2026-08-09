package com.minikun.agent.minikun_agent.api.openai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.pcs.model.ImageSource;

final class ImageAttachmentSelector {
    /** Keeps response payloads small while exposing the first few search results. */
    static final int MAX_ATTACHMENTS = 3;

    private ImageAttachmentSelector() {
    }

    static List<ChatAttachment> select(List<ImageSource> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        Set<String> seenUrls = new LinkedHashSet<>();
        List<ChatAttachment> selected = new ArrayList<>(MAX_ATTACHMENTS);
        for (ImageSource image : images) {
            if (image == null || image.url() == null || image.url().isBlank()
                    || !seenUrls.add(image.url().trim())) {
                continue;
            }
            selected.add(new ChatAttachment("image", image.url(), image.title()));
            if (selected.size() == MAX_ATTACHMENTS) {
                break;
            }
        }
        return List.copyOf(selected);
    }
}