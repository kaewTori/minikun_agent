package com.minikun.goal;

import java.util.List;
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
@ConditionalOnProperty(name = "minikun.task.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/v1/personal/next-actions")
public final class NextActionController {
    private final NextActionService service;
    @Value("${minikun.personal.status.token:${minikun.memory.management.token:}}")
    private String tokenValue;

    public NextActionController(NextActionService service) { this.service = Objects.requireNonNull(service); }

    @GetMapping
    public List<NextActionRecommendation> get(@RequestParam(defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String token) {
        authorize(token);
        return service.recommend(ownerId, limit);
    }

    private void authorize(String token) {
        if (tokenValue != null && !tokenValue.isBlank() && !Objects.equals(tokenValue, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "personal next-action token is invalid");
        }
    }
}
