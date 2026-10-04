package com.minikun.presentation;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/presentations")
@ConditionalOnProperty(name = "minikun.presentation.enabled", havingValue = "true", matchIfMissing = true)
public final class PresentationController {
    private final PresentationService service;
    private final String managementToken;

    PresentationController(PresentationService service,
            @Value("${minikun.presentation.management.token:${minikun.visual.management.token:${minikun.memory.management.token:}}}")
                    String managementToken) {
        this.service = service;
        this.managementToken = managementToken == null ? "" : managementToken.strip();
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable String artifactId,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(name = "X-Minikun-Personal-Token", required = false) String token) {
        authorize(token);
        try {
            PresentationStore.Stored presentation = service.read(artifactId, ownerId);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(PresentationStore.CONTENT_TYPE))
                    .contentLength(presentation.bytes())
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(presentation.filename()).build().toString())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(service.bytes(presentation));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "presentation was not found", exception);
        }
    }

    private void authorize(String suppliedToken) {
        if (!managementToken.isBlank() && !Objects.equals(managementToken, suppliedToken)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "presentation management token is invalid");
        }
    }
}
