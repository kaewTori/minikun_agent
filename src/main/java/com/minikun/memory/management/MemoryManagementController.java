package com.minikun.memory.management;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemoryUpdate;
import com.minikun.memory.MemoryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnBean(MemoryRepository.class)
@RequestMapping("/v1/memory")
public final class MemoryManagementController {
    private final MemoryManagementService service;

    @Value("${minikun.memory.management.token:}")
    private String managementToken;

    public MemoryManagementController(MemoryManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Memory> list(
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        return service.list(ownerId, limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        boolean deleted = service.delete(ownerId, new MemoryId(id));
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id,
            @RequestBody MemoryUpdate update,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        boolean updated = service.update(ownerId, new MemoryId(id), update);
        return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping
    public Map<String, Object> deleteAll(
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        return Map.of("deleted", service.deleteAll(ownerId));
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank()
                && !java.util.Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory management token is invalid");
        }
    }
}
