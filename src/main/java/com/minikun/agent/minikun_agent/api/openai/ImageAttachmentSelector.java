package com.minikun.agent.minikun_agent.api.openai;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.minikun.pcs.model.ImageSource;

final class ImageAttachmentSelector {
    /** Keeps response payloads small while exposing the first few search results. */
    static final int MAX_ATTACHMENTS = 6;

    private ImageAttachmentSelector() {
    }

    static List<ImageSource> select(List<ImageSource> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        Set<String> seenUrls = new LinkedHashSet<>();
        List<ImageSource> selected = new ArrayList<>(MAX_ATTACHMENTS);
        for (ImageSource image : images) {
            if (image == null || image.url() == null || image.url().isBlank()
                    || !seenUrls.add(image.url().trim())) {
                continue;
            }
            selected.add(image);
            if (selected.size() == MAX_ATTACHMENTS) {
                break;
            }
        }
        return List.copyOf(selected);
    }
}
