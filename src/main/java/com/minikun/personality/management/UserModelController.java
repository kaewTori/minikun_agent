package com.minikun.personality.management;

import com.minikun.personality.model.PersonalUserModel;
import com.minikun.personality.profile.UserModelService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Owner-scoped view and reset endpoint for the unified personal user model. */
@RestController
@ConditionalOnBean(UserModelService.class)
@RequestMapping("/v1/user-model")
public final class UserModelController {
    private final UserModelService service;

    @Value("${minikun.memory.management.token:}")
    private String managementToken;

    public UserModelController(UserModelService service) {
        this.service = service;
    }

    @GetMapping
    public PersonalUserModel get(
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        return service.snapshot(ownerId);
    }

    @DeleteMapping
    public Map<String, Object> forget(
            @RequestParam(defaultValue = "default") String ownerId,
            @RequestHeader(value = "X-Minikun-Memory-Token", required = false) String token) {
        authorize(token);
        UserModelService.ForgetResult result = service.forget(ownerId);
        return Map.of(
                "owner_id", result.ownerId(),
                "deleted_preferences", result.deletedPreferences(),
                "deleted_memories", result.deletedMemories(),
                "profile_cleared", result.profileCleared());
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank()
                && !java.util.Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "memory management token is invalid");
        }
    }
}
