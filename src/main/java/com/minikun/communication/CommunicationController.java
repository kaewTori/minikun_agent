package com.minikun.communication;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/communication")
public final class CommunicationController {
    private final CommunicationService communication;

    @Value("${minikun.communication.management.token:${minikun.memory.management.token:}}")
    private String managementToken;

    public CommunicationController(CommunicationService communication) {
        this.communication = Objects.requireNonNull(communication, "communication service must not be null");
    }

    @GetMapping("/status")
    public CommunicationStatus status(
            @RequestHeader(value = "X-Minikun-Communication-Token", required = false) String token) {
        authorize(token);
        return communication.status();
    }

    @PostMapping("/assist")
    public CommunicationResult assist(
            @RequestBody CommunicationApiRequest request,
            @RequestHeader(value = "X-Minikun-Communication-Token", required = false) String token) {
        authorize(token);
        if (request == null) throw new IllegalArgumentException("communication request is required");
        return communication.assist(request.toRequest());
    }

    private void authorize(String token) {
        if (managementToken != null && !managementToken.isBlank() && !Objects.equals(managementToken, token)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "communication management token is invalid");
        }
    }

    public record CommunicationApiRequest(
            String owner_id,
            String action,
            String content,
            String context,
            String goal,
            String audience,
            String channel,
            String tone,
            String language,
            Integer max_length) {

        CommunicationRequest toRequest() {
            return new CommunicationRequest(
                    owner_id == null || owner_id.isBlank() ? "default" : owner_id,
                    action, content, context, goal, audience, channel, tone, language, max_length);
        }
    }
}
