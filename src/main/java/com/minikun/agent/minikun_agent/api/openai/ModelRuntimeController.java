package com.minikun.agent.minikun_agent.api.openai;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.minikun.runtime.ModelsService;

@RestController
@RequestMapping("/v1/models/runtime")
public final class ModelRuntimeController {
    private final ModelsService models;
    private final String managementToken;

    public ModelRuntimeController(
            ModelsService models,
            @Value("${minikun.model.management.token:${minikun.memory.management.token:}}") String managementToken) {
        this.models = models;
        this.managementToken = managementToken;
    }

    @GetMapping
    public ModelsService.Catalog catalog(
            @RequestHeader(value = "X-Minikun-Model-Token", required = false) String token) {
        authorize(token);
        try {
            return models.catalog();
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    @PutMapping
    public ModelsService.Catalog activate(
            @RequestBody Selection selection,
            @RequestHeader(value = "X-Minikun-Model-Token", required = false) String token) {
        authorize(token);
        try {
            return models.activate(selection == null ? null : selection.model());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private ResponseStatusException unavailable(RuntimeException cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Ollama model catalog is unavailable", cause);
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "model management token is invalid");
        }
    }

    public record Selection(String model) {}
}
