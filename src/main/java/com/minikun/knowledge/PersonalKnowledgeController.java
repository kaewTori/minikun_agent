package com.minikun.knowledge;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/knowledge")
public final class PersonalKnowledgeController {
    private final PersonalKnowledgeService knowledge;

    @Value("${minikun.personal-knowledge.management.token:${minikun.memory.management.token:}}")
    private String managementToken;

    public PersonalKnowledgeController(PersonalKnowledgeService knowledge) {
        this.knowledge = Objects.requireNonNull(knowledge, "personal knowledge service must not be null");
    }

    @GetMapping("/status")
    public KnowledgeStatus status(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String token) {
        authorize(token);
        return knowledge.status(ownerId);
    }

    @GetMapping("/sources")
    public List<KnowledgeSourceRecord> sources(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String token) {
        authorize(token);
        return knowledge.sources(ownerId, limit);
    }

    @PostMapping("/index")
    public KnowledgeIndexReport index(
            @RequestBody IndexRequest request,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String token) {
        authorize(token);
        if (request == null) throw new IllegalArgumentException("knowledge index request is required");
        return knowledge.index(request.owner_id(), request.root(), request.path(), request.recursive(), request.force());
    }

    @PostMapping("/reindex")
    public KnowledgeIndexReport reindex(
            @RequestBody(required = false) ReindexRequest request,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String token) {
        authorize(token);
        return knowledge.reindex(request == null ? "default" : request.owner_id(),
                request != null && request.force());
    }

    @PostMapping("/search")
    public List<KnowledgeSearchResult> search(
            @RequestBody SearchRequest request,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String token) {
        authorize(token);
        if (request == null) throw new IllegalArgumentException("knowledge search request is required");
        return knowledge.search(request.owner_id(), request.query(), request.limit() == null ? 5 : request.limit());
    }

    @DeleteMapping("/sources/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String token) {
        authorize(token);
        return knowledge.delete(ownerId, id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "knowledge management token is invalid");
        }
    }

    public record IndexRequest(String owner_id, String root, String path, boolean recursive, boolean force) {}
    public record ReindexRequest(String owner_id, boolean force) {}
    public record SearchRequest(String owner_id, String query, Integer limit) {}
}
