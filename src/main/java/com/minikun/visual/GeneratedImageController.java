package com.minikun.visual;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/images/generated")
public final class GeneratedImageController {
    private final GeneratedImageStore store;

    public GeneratedImageController(GeneratedImageStore store) {
        this.store = store;
    }

    @GetMapping("/{filename:.+}")
    public ResponseEntity<byte[]> image(@PathVariable String filename) {
        try {
            GeneratedImageStore.StoredImageContent image = store.read(filename);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(image.contentType()))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate().immutable())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; script-src 'none'; style-src 'none'; object-src 'none'")
                    .body(image.bytes());
        } catch (ImageGenerationException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage(), exception);
        }
    }
}
