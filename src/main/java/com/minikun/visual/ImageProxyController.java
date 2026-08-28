package com.minikun.visual;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/images")
public final class ImageProxyController {
    private final ImageProxyService service;
    public ImageProxyController(ImageProxyService service) { this.service = service; }

    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxy(@RequestParam String url) {
        ImageProxyService.CachedImage image;
        try { image = service.fetch(url); }
        catch (ImageProxyException exception) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY, exception.getMessage(), exception);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic().immutable())
                .header(HttpHeaders.ETAG, "\"" + image.etag() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(image.bytes());
    }
}
