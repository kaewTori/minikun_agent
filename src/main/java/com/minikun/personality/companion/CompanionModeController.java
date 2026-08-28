package com.minikun.personality.companion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/chat/companion-mode")
public final class CompanionModeController {
    private final CompanionModeService service;
    public CompanionModeController(CompanionModeService service) { this.service = service; }

    @GetMapping
    public ModeResponse get(@RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(name = "conversation_id") String conversationId) {
        return new ModeResponse(service.activeMode(ownerId, conversationId).orElse(CompanionMode.BALANCED));
    }

    @PutMapping
    public ModeResponse set(@RequestBody ModeRequest request) {
        if (request == null) throw new IllegalArgumentException("mode request is required");
        return new ModeResponse(service.setMode(request.owner_id(), request.conversation_id(), request.mode()).mode());
    }

    public record ModeRequest(String owner_id, String conversation_id, CompanionMode mode) {}
    public record ModeResponse(CompanionMode mode) {}
}
