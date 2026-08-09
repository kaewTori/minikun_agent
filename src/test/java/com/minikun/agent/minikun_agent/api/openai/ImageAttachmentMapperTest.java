package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.pcs.model.ImageSource;

class ImageAttachmentMapperTest {
    @Test
    void mapsOnlyCanonicalAttachmentFieldsInOrder() {
        List<ImageSource> images = List.of(
                new ImageSource("image-url", "image title", "source-url", "description"));

        assertEquals(List.of(new ChatAttachment("image", "image-url", "image title")),
                ImageAttachmentMapper.map(images));
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertEquals(List.of(), ImageAttachmentMapper.map(List.of()));
    }
}