package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.pcs.model.ImageSource;

class ImageAttachmentSelectorTest {
    @Test
    void preservesOrderDeduplicatesUrlsAndMapsFields() {
        List<ChatAttachment> attachments = ImageAttachmentSelector.select(List.of(
                image("first-url", "first title"),
                image("first-url", "duplicate title"),
                image("second-url", "second title")));

        assertEquals(List.of(
                new ChatAttachment("image", "first-url", "first title"),
                new ChatAttachment("image", "second-url", "second title")), attachments);
    }

    @Test
    void limitsSelectedImages() {
        List<ChatAttachment> attachments = ImageAttachmentSelector.select(List.of(
                image("one", "one"), image("two", "two"), image("three", "three"),
                image("four", "four")));

        assertEquals(3, attachments.size());
        assertEquals("one", attachments.get(0).url());
        assertEquals("three", attachments.get(2).url());
    }

    @Test
    void emptyOrNullInputProducesEmptyAttachments() {
        assertEquals(List.of(), ImageAttachmentSelector.select(List.of()));
        assertEquals(List.of(), ImageAttachmentSelector.select(null));
    }

    private ImageSource image(String url, String title) {
        return new ImageSource(url, title, "source-url", "description");
    }
}