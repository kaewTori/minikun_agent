package com.minikun.agent.minikun_agent.api.openai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.BackgroundToolScope;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Opt-in answer evaluation on isolated conversations. Operational writes are blocked at the tool boundary. */
@RestController
@RequestMapping("/v1/evals/conversations")
public final class ConversationEvalController {
    private final ChatService chat;
    private final String token;
    private final List<Scenario> scenarios;

    public ConversationEvalController(ChatService chat, ObjectMapper json,
            @Value("${minikun.eval.management.token:${minikun.memory.management.token:}}") String token) throws IOException {
        this.chat = chat;
        this.token = token == null ? "" : token.trim();
        List<Scenario> loaded = new ArrayList<>();
        for (String file : List.of("conversations.json", "intelligence.json")) {
            try (var input = getClass().getResourceAsStream("/evals/" + file)) {
                if (input == null) throw new IOException("conversation eval suite is missing: " + file);
                loaded.addAll(json.readValue(input, new TypeReference<List<Scenario>>() {}));
            }
        }
        this.scenarios = List.copyOf(loaded);
    }

    @GetMapping
    public List<Scenario> scenarios(@RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        return scenarios;
    }

    @PostMapping("/{id}")
    public Result evaluate(@PathVariable String id,
            @RequestHeader(value = "X-Minikun-Personal-Token", required = false) String supplied) {
        authorize(supplied);
        Scenario scenario = scenarios.stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "eval scenario not found"));
        UUID operation = UUID.randomUUID();
        String owner = "eval-" + operation;
        List<Message> messages = new ArrayList<>();
        List<String> responseIds = new ArrayList<>();
        long started = System.nanoTime();
        String answer = "";
        // A model cannot override this scope to write to the user's tasks, files, or other tools.
        try (var scope = new BackgroundToolScope(operation, true)) {
            for (String turn : scenario.turns()) {
                messages.add(new Message("user", turn));
                var response = chat.chatCompletion(new ChatCompletionRequest("mini-kun", List.copyOf(messages),
                        owner, false, 0.0, 600, null, owner), new ConversationId(owner));
                answer = response.choices().getFirst().message().content();
                responseIds.add(response.id());
                messages.add(new Message("assistant", answer));
            }
            Result scored = score(scenario, answer, scope.blocked(), (System.nanoTime() - started) / 1_000_000);
            return new Result(scored.id(), scored.passed(), scored.failures(), scored.elapsedMs(), scored.answer(),
                    scored.rubric(), owner, List.copyOf(responseIds));
        }
    }

    static Result score(Scenario scenario, String answer, boolean blockedWrite, long elapsedMs) {
        String text = answer == null ? "" : answer;
        List<String> failures = new ArrayList<>();
        if (text.isBlank()) failures.add("empty_answer");
        for (String required : scenario.mustContain()) if (!text.contains(required)) failures.add("missing:" + required);
        for (String forbidden : scenario.mustNotContain()) if (text.contains(forbidden)) failures.add("unexpected:" + forbidden);
        if (!scenario.anyOf().isEmpty() && scenario.anyOf().stream().noneMatch(text::contains)) failures.add("missing_expected_alternative");
        if (scenario.citationRequired() && !text.matches("(?s).*\\[[^]]+\\]\\(https?://[^)]+\\).*")) failures.add("missing_citation");
        if (scenario.maxCharacters() > 0 && text.length() > scenario.maxCharacters()) failures.add("too_long");
        if (blockedWrite) failures.add("attempted_write");
        return new Result(scenario.id(), failures.isEmpty(), List.copyOf(failures), elapsedMs, text, scenario.rubric(), "", List.of());
    }

    private void authorize(String supplied) {
        // Unlike cheap routing evals this endpoint spends model capacity, so require a configured token.
        if (token.isBlank() || !token.equals(supplied)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "eval token required");
    }

    public record Scenario(String id, List<String> turns, List<String> mustContain, List<String> mustNotContain,
            List<String> anyOf, boolean citationRequired, int maxCharacters, String rubric) { }
    public record Result(String id, boolean passed, List<String> failures, long elapsedMs, String answer, String rubric,
            String conversationId, List<String> responseIds) { }
}
