package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Owner-operated regression endpoint; it never sends messages or executes tools. */
@RestController
@RequestMapping("/v1/evals/turn-plans")
public final class EvalLabController {
    private final TurnEvaluationService evaluations;
    private final String token;

    public EvalLabController(TurnEvaluationService evaluations,
            @Value("${minikun.eval.management.token:${minikun.memory.management.token:}}") String token) {
        this.evaluations = evaluations;
        this.token = token == null ? "" : token.trim();
    }

    @GetMapping("/baseline")
    public TurnEvaluationService.Report baseline(
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        return evaluations.baseline();
    }

    @PostMapping
    public TurnEvaluationService.Report evaluate(
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied,
            @RequestBody SuiteRequest request) {
        authorize(supplied);
        if (request == null) throw new IllegalArgumentException("eval suite is required");
        return evaluations.evaluate(request.cases());
    }

    @GetMapping("/quality")
    public TurnEvaluationService.QualityReport quality(
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied,
            @RequestParam(name = "owner_id", defaultValue = "default") String ownerId,
            @RequestParam(defaultValue = "100") int limit) {
        authorize(supplied);
        return evaluations.quality(ownerId, limit);
    }

    private void authorize(String supplied) {
        if (!token.isBlank() && !token.equals(supplied)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "eval management token is invalid");
        }
    }

    public record SuiteRequest(List<TurnEvaluationService.Case> cases) { }
}
