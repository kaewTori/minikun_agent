package com.minikun.agent.execution;

import com.minikun.sync.DevicePairingService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/agent/actions")
public final class AgentActionController {
    private final AgentActionRuntime runtime;
    private final AgentExecutionService executions;
    private final ObjectProvider<DevicePairingService> devices;
    private final String token;
    public AgentActionController(AgentActionRuntime runtime, AgentExecutionService executions,
            ObjectProvider<DevicePairingService> devices, @Value("${minikun.agent.execution.management.token:}") String token) {
        this.runtime = runtime; this.executions = executions; this.devices = devices; this.token = token;
    }

    @GetMapping public List<AgentActionRuntime.View> list(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner) {
        authorize(request, owner);
        return executions.list(owner, null, 50).stream().filter(run -> runtime.hasAction(owner, run.id())).map(run -> runtime.view(owner, run.id())).toList();
    }
    @GetMapping("/{id}") public AgentActionRuntime.View get(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @PathVariable UUID id) {
        authorize(request, owner); return runtime.view(owner, id);
    }
    public record Start(String conversationId, String idempotencyKey, AgentActionPlan plan) { }
    @PostMapping public AgentActionCheckpoint start(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @RequestBody Start body) {
        authorize(request, owner);
        if (body.idempotencyKey() == null || body.idempotencyKey().isBlank() || body.idempotencyKey().length() > 200) throw new IllegalArgumentException("idempotencyKey required, at most 200 characters");
        return runtime.start(owner, body.conversationId(), body.idempotencyKey(), body.plan());
    }
    public record Decision(String digest, boolean approve) { }
    @PostMapping("/{id}/decision") public AgentActionCheckpoint decide(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @PathVariable UUID id, @RequestBody Decision decision) {
        authorize(request, owner); return runtime.decide(owner, id, decision.digest(), decision.approve());
    }
    @PostMapping("/{id}/cancel") public AgentActionCheckpoint cancel(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @PathVariable UUID id) {
        authorize(request, owner); return runtime.cancel(owner, id);
    }
    @PostMapping("/{id}/review") public AgentActionCheckpoint review(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @PathVariable UUID id) {
        authorize(request, owner); return runtime.review(owner, id);
    }
    @GetMapping("/grants") public List<AgentActionStore.Grant> grants(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner) {
        authorize(request, owner); return runtime.grants(owner);
    }
    public record GrantRequest(String tool, String action, String resource, Instant expiresAt, int maxUses, int cooldownSeconds, String monitorComponent) { }
    @PostMapping("/grants") public AgentActionStore.Grant grant(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @RequestBody GrantRequest body) {
        authorize(request, owner); return runtime.grant(owner, body.tool(), body.action(), body.resource(), body.expiresAt(), body.maxUses(), body.cooldownSeconds(), body.monitorComponent());
    }
    @DeleteMapping("/grants/{id}") public Map<String, Boolean> revoke(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @PathVariable UUID id) {
        authorize(request, owner); runtime.revoke(owner, id); return Map.of("revoked", true);
    }
    @GetMapping("/catalog") public Map<String, Object> catalog(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner) {
        authorize(request, owner); return runtime.catalog();
    }
    @GetMapping("/browser-page") public Object browserPage(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @RequestParam String url) {
        authorize(request, owner); return runtime.browserSnapshot(owner, url);
    }
    @PostMapping("/repair") public AgentActionCheckpoint repair(HttpServletRequest request, @RequestParam(name="owner_id", defaultValue="default") String owner, @RequestBody Repair body) {
        authorize(request, owner); return runtime.start(owner, body.conversationId(), body.idempotencyKey(), AgentActionRuntime.repairPlan(body.component(), body.actionId()));
    }
    public record Repair(String conversationId, String idempotencyKey, String component, String actionId) { }

    private void authorize(HttpServletRequest request, String owner) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-site action requests blocked");
        String origin = request.getHeader("Origin");
        if (origin != null) {
            URI uri = URI.create(origin);
            int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            if (!Objects.equals(uri.getScheme(), request.getScheme()) || !request.getServerName().equalsIgnoreCase(uri.getHost()) || port != request.getServerPort()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-origin action requests blocked");
        }
        if (!token.isBlank() && java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8), Objects.toString(request.getHeader("X-Minikun-Agent-Token"), "").getBytes(java.nio.charset.StandardCharsets.UTF_8))) return;
        var pairing = devices.getIfAvailable();
        if (pairing == null || !pairing.require(request).ownerId().equals(owner)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "pair an owner device or configure the agent management token");
    }
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class}) public ResponseEntity<Map<String, String>> invalid(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", Objects.toString(exception.getMessage(), "Action unavailable")));
    }
}
