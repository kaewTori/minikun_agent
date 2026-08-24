package com.minikun.systemhealth;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only, sanitized host health endpoint for the personal Cockpit. */
@RestController
@RequestMapping("/v1/system/health")
public final class SystemHealthController {
    private final SystemHealthReader health;

    @Value("${minikun.system.health.management-token:${minikun.memory.management.token:}}")
    private String managementToken;

    public SystemHealthController(SystemHealthReader health) {
        this.health = Objects.requireNonNull(health, "system health reader must not be null");
    }

    @GetMapping
    public SystemHealthReport report(
            @RequestHeader(value = "X-Minikun-System-Token", required = false) String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "system health management token is invalid");
        }
        return health.read();
    }
}
