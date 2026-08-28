package com.minikun.personalcare;

import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = {"minikun.task.enabled", "minikun.goal.enabled"},
        havingValue = "true", matchIfMissing = true)
@RequestMapping("/v1/personal/status")
public final class PersonalCareStatusController {
    private final PersonalCareStatusService service;
    @Value("${minikun.personal.status.token:${minikun.goal.management.token:${minikun.memory.management.token:}}}")
    private String tokenValue;

    public PersonalCareStatusController(PersonalCareStatusService service) { this.service = Objects.requireNonNull(service); }

    @GetMapping
    public Map<String, Object> get(@RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) {
        if (tokenValue != null && !tokenValue.isBlank() && !Objects.equals(tokenValue, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "personal status token is invalid");
        }
        return service.snapshot(ownerId);
    }
}
