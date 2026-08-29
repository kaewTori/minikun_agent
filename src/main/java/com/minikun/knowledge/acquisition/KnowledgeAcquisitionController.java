package com.minikun.knowledge.acquisition;

import static com.minikun.knowledge.acquisition.KnowledgeAcquisitionModels.*;

import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/knowledge/acquisition")
@ConditionalOnProperty(name = "minikun.knowledge-acquisition.enabled", havingValue = "true", matchIfMissing = true)
public final class KnowledgeAcquisitionController {
    private final KnowledgeAcquisitionService service;
    private final String token;

    KnowledgeAcquisitionController(KnowledgeAcquisitionService service,
            @Value("${minikun.knowledge-acquisition.management.token:${minikun.memory.management.token:}}")
            String token) {
        this.service = service;
        this.token = token == null ? "" : token.strip();
    }

    @GetMapping("/topics")
    public List<Topic> topics(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(required = false) TopicStatus status, @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        return service.topics(ownerId, status, limit);
    }

    @PostMapping("/topics")
    public Topic create(@RequestBody TopicRequest request,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("topic request is required");
        return service.createTopic(owner(request.owner_id()), request.name(), request.objective(), request.origin(),
                request.priority(), request.refresh_policy(), request.source_policy(), request.trusted_domains(),
                request.status());
    }

    @PatchMapping("/topics/{id}")
    public Topic update(@PathVariable UUID id, @RequestBody TopicRequest request,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("topic request is required");
        return service.updateTopic(owner(request.owner_id()), id, request.name(), request.objective(), request.priority(),
                request.refresh_policy(), request.source_policy(), request.trusted_domains(), request.status());
    }

    @DeleteMapping("/topics/{id}")
    public void delete(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        if (!service.deleteTopic(ownerId, id)) throw new IllegalArgumentException("knowledge topic was not found");
    }

    @PostMapping("/topics/{id}/runs")
    public AcquisitionRun run(@PathVariable UUID id,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        return service.runNow(ownerId, id);
    }

    @GetMapping("/runs")
    public List<AcquisitionRun> runs(
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(name = "topic_id", required = false) UUID topicId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        return service.runs(ownerId, topicId, limit);
    }

    @GetMapping("/claims")
    public List<Claim> claims(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(required = false) ClaimStatus status, @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        return service.claims(ownerId, status, limit);
    }

    @PatchMapping("/claims/{id}")
    public Claim review(@PathVariable UUID id, @RequestBody ClaimReviewRequest request,
            @RequestHeader(value = "X-Minikun-Knowledge-Token", required = false) String supplied) {
        authorize(supplied);
        if (request == null || request.status() == null) {
            throw new IllegalArgumentException("claim review status is required");
        }
        return service.reviewClaim(owner(request.owner_id()), id, request.status());
    }

    private void authorize(String supplied) {
        if (!token.isBlank() && !token.equals(supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "knowledge token is invalid");
        }
    }

    private String owner(String value) {
        return value == null || value.isBlank() ? "default" : value;
    }

    public record TopicRequest(String owner_id, String name, String objective, TopicOrigin origin,
            Integer priority, RefreshPolicy refresh_policy, SourcePolicy source_policy,
            List<String> trusted_domains, TopicStatus status) { }

    public record ClaimReviewRequest(String owner_id, ClaimStatus status) { }
}
