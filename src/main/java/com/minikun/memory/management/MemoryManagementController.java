package com.minikun.memory.management;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/memory")
public final class MemoryManagementController {
    private final MemoryManagementService service;

    public MemoryManagementController(MemoryManagementService service) {
        this.service = service;
    }

    @GetMapping
    public List<Memory> list(
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit) {
        return service.list(ownerId, limit);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestParam(defaultValue = "default") String ownerId,
            @PathVariable UUID id) {
        boolean deleted = service.delete(ownerId, new MemoryId(id));
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping
    public Map<String, Object> deleteAll(
            @RequestParam(defaultValue = "default") String ownerId) {
        return Map.of("deleted", service.deleteAll(ownerId));
    }
}
