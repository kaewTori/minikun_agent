package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.pcs.model.ImageSource;

class ImageAttachmentMapperTest {
    @Test
    void mapsVisualMetadataAndUsesTheSafeProxy() {
        List<ImageSource> images = List.of(
                new ImageSource("https://images.example/image.jpg", "image title", "source-url", "description"));

        ChatAttachment attachment = ImageAttachmentMapper.map(images).getFirst();
        assertEquals("/v1/images/proxy?url=https%3A%2F%2Fimages.example%2Fimage.jpg", attachment.url());
        assertEquals("https://images.example/image.jpg", attachment.originalUrl());
        assertEquals("source-url", attachment.sourceUrl());
        assertEquals("description", attachment.description());
        assertEquals("web", attachment.origin());
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertEquals(List.of(), ImageAttachmentMapper.map(List.of()));
    }
}
