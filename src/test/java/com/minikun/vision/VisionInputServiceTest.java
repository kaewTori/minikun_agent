package com.minikun.vision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Clock;
import java.nio.file.Path;
import java.util.Base64;

import com.minikun.visual.GeneratedImageStore;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import org.junit.jupiter.api.Test;

class VisionInputServiceTest {
    private static final String PNG = "data:image/png;base64,iVBORw0KGgo=";
    @TempDir Path directory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolvesValidDataUrlWithoutKeepingTheSourceUrl() throws Exception {
        VisionInput input = service(3, 1024).resolve(message(PNG));

        assertEquals(1, input.imageCount());
        assertEquals("image/png", input.media().getFirst().getMimeType().toString());
        assertArrayEquals(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a},
                input.media().getFirst().getDataAsByteArray());
    }

    @Test
    void acceptsMultipleImagesWithinLimit() throws Exception {
        Message message = objectMapper.readValue("""
                {"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"%s"}},
                  {"type":"image_url","image_url":{"url":"%s"}}
                ]}
                """.formatted(PNG, PNG), Message.class);

        assertEquals(2, service(2, 1024).resolve(message).imageCount());
    }

    @Test
    void rejectsTooManyImages() throws Exception {
        Message message = objectMapper.readValue("""
                {"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"%s"}},
                  {"type":"image_url","image_url":{"url":"%s"}}
                ]}
                """.formatted(PNG, PNG), Message.class);

        assertThrows(VisionInputException.class, () -> service(1, 1024).resolve(message));
    }

    @Test
    void rejectsOversizedAndMismatchedImages() throws Exception {
        assertThrows(VisionInputException.class, () -> service(3, 7).resolve(message(PNG)));
        assertThrows(VisionInputException.class, () -> service(3, 1024).resolve(
                message("data:image/jpeg;base64,iVBORw0KGgo=")));
    }

    @Test
    void rejectsUnsupportedMimeAndInvalidBase64() throws Exception {
        assertThrows(VisionInputException.class, () -> service(3, 1024).resolve(
                message("data:image/gif;base64,R0lGODlh")));
        assertThrows(VisionInputException.class, () -> service(3, 1024).resolve(
                message("data:image/png;base64,not-base64")));
    }

    @Test
    void blocksLocalAndInsecureRemoteUrlsBeforeDownloading() throws Exception {
        assertThrows(VisionInputException.class, () -> service(3, 1024).resolve(
                message("https://127.0.0.1/private.png")));
        assertThrows(VisionInputException.class, () -> service(3, 1024).resolve(
                message("http://example.com/image.png")));
    }

    @Test
    void resolvesGeneratedImageReferenceAndKeepsValidation() throws Exception {
        var store = new GeneratedImageStore(directory, 1024, Clock.systemUTC());
        byte[] bytes = Base64.getDecoder().decode(PNG.substring(PNG.indexOf(',') + 1));
        var saved = store.save(bytes);
        var service = new VisionInputService(true, 3, 1024, false, false,
                Duration.ofSeconds(1), Duration.ofSeconds(1), store);

        var input = service.resolve(message(saved.url()));
        assertEquals("image/png", input.media().getFirst().getMimeType().toString());
        assertArrayEquals(bytes, input.media().getFirst().getDataAsByteArray());
        assertThrows(VisionInputException.class, () -> service(3, 7).resolve(message(saved.url())));
        for (String url : new String[] {
                "/v1/images/generated/../secret.png",
                "/v1/images/generated/%2e%2e%2fsecret.png",
                "/v1/images/generated/00000000-0000-0000-0000-000000000000.png",
                "/v1/images/generated/" + saved.filename() + "?other=1",
                "/other/local.png"}) {
            assertThrows(VisionInputException.class, () -> service(3, 1024).resolve(message(url)));
        }
    }

    private Message message(String url) throws Exception {
        return objectMapper.readValue("""
                {"role":"user","content":[{"type":"image_url","image_url":{"url":"%s"}}]}
                """.formatted(url), Message.class);
    }

    private VisionInputService service(int maxImages, int maxBytes) {
        return new VisionInputService(true, maxImages, maxBytes, true, false,
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                new GeneratedImageStore(directory, 1024, Clock.systemUTC()));
    }
}
