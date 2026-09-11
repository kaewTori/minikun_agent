# Logic reliability and evaluation

This change tightens the existing agent loop; it does not add another model, database, or agent framework.

## Recovery

- Resuming failed steps stops immediately on failure or confirmation. Later steps may depend on that result.
- Resume and generic risk confirmation never automatically retry writes. An error can mean the write happened but its response was lost.
- Background recovery reuses the job ID and starts in read-only mode. `DefaultToolExecutor` checks the actual tool invocation, including deterministic routers. A newly generated tool-call ID cannot bypass recovery protection.
- Recovered writes return `REVIEW_REQUIRED`; background jobs expose `review_required` and notify the user to inspect the existing result. Review the original task/reminder/file before submitting a new write in its original conversation.
- Cancellation is checked against job state as well as thread interruption. A cancelled worker must finish before the job can resume. A tool already executing cannot be rolled back by cancellation.
- The scope follows the current synchronous tool-calling worker. If tool execution is later moved to a separate thread pool, explicitly propagate this scope before enabling that change. Resume locking is for the current single-server deployment.

This deliberately prevents replayed writes instead of claiming exactly-once execution against arbitrary external systems. Read-only recovery restarts reasoning from the saved request; it does not restore the model's hidden state.

## Completion evidence

Task create/update/complete and reminder create/update/snooze now read the scoped record back and compare requested outcome fields, including title, time, timezone and state. A write acknowledgement without a matching record is an error. Cancellation/acknowledgement tools cannot report success when the domain operation returns false.

A successful tool step still means that invocation completed. A natural-language multi-step plan is marked `UNVERIFIED` when the model stops, unless there are failures or outstanding confirmations, which retain their respective states. The answer includes a notice that overall plan coverage has not been verified. We do not infer coverage from tool-call count or trust a model's unsupported declaration of completion. `COMPLETED` remains readable for historical records; this loop no longer emits it for unverified plans.

## Memory and optional context

Memory candidate selection searches across the owner's corpus using bound query terms, mixes in recent memories, then uses the existing semantic/lexical ranker. Thai tokenization uses the JDK word iterator. The formatter preserves relevance order so a character limit cannot prioritize recent unrelated facts over the best match.

The query-time lexical scan has an O(owner memory count) ceiling and cannot discover every synonym without matching words. Add indexed retrieval only when profiling justifies it; existing semantic ranking still operates on the selected candidates.

`MINIKUN_CHAT_CONTEXT_TIMEOUT` defaults to `3s`, shared across optional summary, long-term memory and personal knowledge waits in one turn. Ready results survive a slow sibling; timeout cancels the worker and falls back to available context. Client libraries must honor interruption or their own I/O timeouts for cancellation to release resources promptly. This budget does not cap model generation, required actions, explicit web evidence, or deep research.

Timers `minikun.chat.stage.duration` with stages `summary_wait`, `memory_wait`, and `personal_wait` distinguish `success`, `timeout`, `fallback`, and `cancelled`. Plain greetings bypass retrieval and the search-decision model.

## Repeatable checks

Run the normal suite with `./mvnw test`. For the PostgreSQL recall integration check, point the following variables at a disposable database. The check uses only a connection-local temporary table:

```sh
MINIKUN_EVAL_JDBC_URL=jdbc:postgresql://127.0.0.1:55439/minikun_eval \
MINIKUN_EVAL_JDBC_USER=minikun \
./mvnw -Dtest=JdbcMemoryRecallIntegrationTest test
```

The live answer suite contains 24 conversation scenarios plus 96 Thai/temporal/reasoning scenarios in `src/main/resources/evals/`: contextual references, corrections, constraints, uncertainty, evidence, temporal updates and bounded reasoning. The controller exposes all 120 with the same isolated-owner guard. Operational recovery, confirmation stops, persistence checks, old-memory retrieval and timeouts are covered separately by automated Java tests.

Run live evaluation against a **test deployment of this revision** with `MINIKUN_EVAL_MANAGEMENT_TOKEN` configured on both the server and shell:

```sh
python3 tools/eval-conversations.py --self-test
python3 tools/eval-conversations.py --url http://127.0.0.1:8080 --output /tmp/minikun-before.json
# After changing the model, prompt, or routing:
python3 tools/eval-conversations.py --url http://127.0.0.1:8080 \
  --baseline /tmp/minikun-before.json --output /tmp/minikun-after.json
```

`--case thai-name-correction` runs one scenario. GET `/v1/evals/conversations` lists the suite; POST `/v1/evals/conversations/{id}` runs one case. Both require a configured token. Each case uses a fresh owner/conversation and blocks operational writes at the executor. It still consumes model/search capacity and can persist its own isolated conversation/adaptation data, so use a test deployment.

Reports retain answers, contract failures, per-scenario elapsed time, p50/p95 and cases that regressed from pass to fail. A contract pass is not a factual-quality score: review every answer against its rubric, inspect tool traces for argument correctness, and open citations to verify support. Timing includes all turns in a scenario; compare the same suite, model, machine and warm/cold conditions. No live answer-quality or production latency improvement is claimed from unit tests alone.

For model-only comparison of Thai semantics, temporal memory and reasoning, run the deterministic harness against a disposable local Ollama model:

```sh
python3 tools/eval-intelligence.py --self-test
python3 tools/eval-intelligence.py --model hf.co/HauhauCS/Gemma4-12B-QAT-Uncensored-HauhauCS-Balanced:Q4_K_M \
  --tokens 1024 --output docs/evals/intelligence-gemma4-baseline.json
```

The harness covers 96 synthetic cases and does not prove persistence correctness or all Thai dialect coverage. A Qwen3 run that leaks `<think>` into content is marked invalid; keep it as a backend diagnosis rather than a score.
