# TinyGrad migration runbook

This runbook keeps the Ollama path available while Mini-kun moves its primary
chat inference to the local TinyGrad/Gemma 4 runtime.

## Reproducible baseline

The machine-readable baseline is
`/Volumes/minikun/homelab/java/script/tinygrad-runtime.baseline.json`. It
pins the TinyGrad commit, GGUF size and SHA-256, context window, runtime flags,
and the operator-observed 37 tokens/second decode result.

Start that exact baseline and wait for semantic readiness with one supervised
command:

```sh
MINIKUN_TINYGRAD_VERIFY_MODEL=1 /Volumes/minikun/homelab/java/script/start-tinygrad.sh
```

The checksum is optional after the first verified launch because hashing the
7.5 GiB model adds startup I/O. The script rejects a different TinyGrad commit
and normally rejects a dirty TinyGrad worktree. During local development only,
exercise the current uncommitted server patch with:

```sh
/Volumes/minikun/homelab/java/script/start-tinygrad.sh
```

The wrapper starts the server, supervises its PID, runs the full `/health` and
`/models` readiness validation, writes `/tmp/tinygrad-readiness.json`, and then
waits on the server. If startup exits or readiness fails, it stops the child
instead of leaving a partial runtime behind. Set
`MINIKUN_TINYGRAD_READY_WAIT_SECONDS` or `MINIKUN_TINYGRAD_READY_OUTPUT` to
override its 600-second deadline or evidence path. It also enables the dirty
worktree override because this is the local entry point for the in-development
fork. The lower-level `start-tinygrad-baseline.sh` remains strict when invoked
directly for a reproducible production launch.

The launcher also checks for the patched `--model_contexts` interface, so a
clean upstream baseline cannot silently pass validation and then fail on its
parallel-serving flags. The dirty override must not be used for production.
Commit the reviewed TinyGrad patch and update both the manifest commit and the
launcher's default expected commit before production activation.

The launcher uses `/Volumes/minikun/homelab/tinygrad/.venv/bin/python` by
default, equivalent to activating that repository environment first. Override
it with `MINIKUN_TINYGRAD_PYTHON` only when intentionally testing another
interpreter. Validate the runtime without binding a port or loading the model:

```sh
MINIKUN_TINYGRAD_ALLOW_DIRTY=1 MINIKUN_TINYGRAD_VALIDATE_ONLY=1 \
  /Volumes/minikun/homelab/java/script/start-tinygrad-baseline.sh
```

`--serve` already runs TinyGrad warmup, so `--warmup` is intentionally omitted.
`--fast` forces beam search off; `BEAM=8` and `JITBEAM=8` did not affect the
original serving command and are intentionally pinned to zero.

## macOS eGPU and NVCC topology

The production process is split across two boundaries:

1. `/Volumes/minikun/homelab/tinygrad/.venv/bin/python -m tinygrad.llm` runs on
   the macOS host. `DEV=NV` uses TinyGrad's direct NV backend to allocate model,
   KV-cache, and working buffers on the eGPU.
2. Colima runs `cuda-nvcc-persistent` from `cuda-nvcc:12.8`. It has no NVIDIA
   device passed through and only compiles CUDA source through `nvccshim`.

`docker stats` and memory reported inside `cuda-nvcc-persistent` therefore
measure the compiler container's Linux CPU memory, not inference VRAM. The
canonical cross-boundary buffer measurement is
`/v1/health.allocator_memory`: use `devices.NV.current_bytes` for currently
live NV allocations and `devices.NV.peak_bytes` for the process-lifetime high
water mark. The aggregate `current_bytes` and `peak_bytes` can also include
non-NV devices. These allocator counters do not include driver/GSP bookkeeping
or every form of physical residency, so the rollout gate defaults to a 15 GiB
allocator peak on the 16 GiB card instead of treating the card's full nominal
capacity as usable.

The macOS NVCC path reuses `cuda-nvcc-persistent`; it must not start a new
`docker run --rm` container for every JIT compile. Install or refresh the shim
with `extra/setup_nvcc_osx.sh`. The launcher passes the absolute
`$HOME/.local/bin/nvcc` path, so operators do not need to modify `PATH`; set
`NVCC` only for an intentional alternate shim. Use host-context Docker
diagnostics. A restricted sandbox can be denied access to the Colima socket and
incorrectly report that Colima is stopped.

## Activation and rollback

Keep Mini-kun on Ollama while validating the baseline:

```sh
export MINIKUN_MODEL_ACTIVE=existing
```

After the TinyGrad smoke, quality, and load gates pass, activate TinyGrad for
primary chat and align Mini-kun's declared context window:

```sh
export MINIKUN_MODEL_ACTIVE=tinygrad
export MINIKUN_TINYGRAD_BASE_URL=http://127.0.0.1:8001/v1
export MINIKUN_TINYGRAD_MODEL=gemma-4-E4B-it-ultra-uncensored-heretic-Q8_0
export MINIKUN_TINYGRAD_CONTEXT_WINDOW=32768
export MINIKUN_TINYGRAD_TOOLS_ENABLED=true
export MINIKUN_TINYGRAD_FAILOVER_ENABLED=true
export MINIKUN_MODEL_TASK_PROVIDER=tinygrad
export MINIKUN_MODEL_TITLE_PROVIDER=tinygrad
export MINIKUN_MODEL_TASK_FAILOVER_ENABLED=true
export MINIKUN_MODEL_TASK_FAILOVER_COOLDOWN=PT30S
```

For the staged local launch, keep TinyGrad running and use the supervised deploy
wrapper instead of setting these values by hand:

```sh
/Volumes/minikun/homelab/java/script/deploy-minikun-tinygrad-chat.sh
```

It requires TinyGrad semantic readiness, deploys the new Mini-kun JAR, activates
TinyGrad for chat with retryable Ollama fallback, and explicitly keeps task and
title providers on Ollama. A failed deployment restores Ollama automatically.
Manual rollback is one command:

```sh
/Volumes/minikun/homelab/java/script/rollback-minikun-ollama.sh
```

With failover enabled, Mini-kun retries on Ollama only for connection/timeout,
HTTP 429, or HTTP 5xx failures. A stream can fail over only before its first
chunk; a partially emitted TinyGrad response is never mixed with Ollama output.
HTTP 4xx and invalid model/protocol responses remain visible instead of being
masked by rollback.

The background TaskModel has its own TinyGrad-to-Ollama failover. Connection,
timeout, HTTP 429, and HTTP 5xx failures open a 30-second circuit cooldown that routes subsequent
title, summary, reflection, search-reasoning, memory, and knowledge work
directly to the configured Ollama task model instead of repeatedly paying the
failed TinyGrad timeout. A successful TinyGrad probe after the cooldown closes
the circuit. HTTP 4xx and malformed responses are not masked. Set
`MINIKUN_MODEL_TASK_FAILOVER_ENABLED=false` only for diagnosis when the
underlying TinyGrad error must remain unmasked.

Rollback is the single environment change
`MINIKUN_MODEL_ACTIVE=existing` followed by a Mini-kun restart. Do not remove
the Ollama dependency or its model until the final soak milestone completes.

## Gates before primary activation

1. `/v1/models`, synchronous chat, streaming with usage, client cancellation,
   context overflow, and malformed/error responses pass.
2. Golden Thai/MCS prompts meet the Ollama baseline for persona, factuality,
   structured output, long context, and refusal behavior.
3. Gemma 4 renders tool definitions, assistant tool calls, and tool results;
   only then may `TinyGradChatModelProvider` advertise tool calling.
4. Concurrent requests are isolated by session and KV state, bounded by a
   priority queue, and background work cannot starve interactive chat.
5. Soak metrics cover latency p50/p95/p99, time-to-first-token, prefill and
   decode throughput, cancellation, queue depth/wait, errors, and fallback.

## Golden quality evaluation

The deterministic fixture and runner are stored in
`/Volumes/minikun/homelab/java/script/tinygrad/tinygrad-quality-cases.json` and
`/Volumes/minikun/homelab/java/script/tinygrad/tinygrad-quality-eval.py`. The runner reconstructs the same compact
MCS persona emitted by `CorePromptFragments`, disables hidden Ollama thinking,
normalizes OpenAI/Ollama tool calls and tool results, and stores every raw
response plus check result in its JSON report. Run the fast nine-case gate with:

```sh
/Volumes/minikun/homelab/tinygrad/.venv/bin/python \
  /Volumes/minikun/homelab/java/script/tinygrad/tinygrad-quality-eval.py \
  --skip-category long_context \
  --fail-under 1.0 \
  --output /tmp/tinygrad-quality-fast.json
```

Run `--case long_context_needle` separately because it supplies a measured
26,764-token prompt. It deliberately leaves about 5,700 tokens of 32K context
headroom so context truncation cannot masquerade as a retrieval failure.

Calibrated controls on the target host were:

| Provider | Fast cases | 26.7K needle | Important result |
| --- | ---: | ---: | --- |
| current Ollama Gemma 4 E2B Q8 | 8/9 | fail, 69.36 s | leaked the system secret and missed the long-context needle |
| Ollama Gemma 4 E4B Q4_K_M | 9/9 after evaluator calibration | pass, 108.37 s | E4B establishes the 10/10 model-quality target |
| TinyGrad Gemma 4 E4B Q8 | persona pass | pending GPU reset | 848-token production persona took 40.45 s |

The TinyGrad persona response correctly identified itself as Minikun, called
the user `พี่สาว`, and described the younger-brother relationship. Server
telemetry measured 848 prompt tokens at about 23 prefill tok/s; generation was
about 29 tok/s. Quality is therefore acceptable for that case, while prefill
latency is not yet acceptable for primary activation.

Mini-kun records provider-specific `model_tinygrad_call`,
`model_tinygrad_stream`, and `model_tinygrad_ttft` stages through the existing
`minikun.chat.stage.duration` meter. Task/title requests are marked
`priority=background`; user chat is marked `priority=interactive`.
Conversation title, summary, reflection, and durable-memory extraction now use
the TinyGrad-backed TaskModel path. AI knowledge ranking and relevance use that
same background path. Ollama remains configured for embeddings until a
separately benchmarked embedding runtime replaces it.

## Embeddings and vision during migration

Chat inference and embeddings are intentionally independent. Activating
TinyGrad changes `ModelsService.chatModel()` to the TinyGrad model while Spring
AI's `EmbeddingModel` remains backed by Ollama
`qwen3-embedding:0.6b`. Semantic memory and personal knowledge already degrade
to lexical scoring if embeddings become unavailable; the system health list
keeps both `tinygrad:8001` and `ollama:11434` visible during this phase.

Run the embedding invariant gate directly against Ollama and through Minikun's
OpenAI adapter:

```sh
/Volumes/minikun/homelab/tinygrad/.venv/bin/python /Volumes/minikun/homelab/java/script/tinygrad/embedding-smoke.py \
  --output /tmp/minikun-embedding-ollama.json

/Volumes/minikun/homelab/tinygrad/.venv/bin/python /Volumes/minikun/homelab/java/script/tinygrad/embedding-smoke.py \
  --kind openai --base-url http://127.0.0.1:8080/v1 \
  --output /tmp/minikun-embedding-openai.json
```

The target-host baseline returned three finite, unit-normalized 1024-dimensional
vectors. Direct Ollama latency was 0.17 seconds after warmup with duplicate
cosine 0.99996342; the end-to-end Minikun request took 0.47 seconds with
duplicate cosine 1.0.

TinyGrad's current Gemma 4 server is text-only. When TinyGrad primary and
failover are enabled, `FailoverChatModelProvider` exposes the union of primary
and fallback capabilities and routes unsupported vision, tool-calling, or
streaming requests directly to the capable Ollama fallback. It preserves image
media and portable tool options, uses the configured Ollama fallback model
rather than leaking the TinyGrad model name, and never invokes the unsupported
primary first. If neither provider supports a capability, the existing API
validation still rejects the request.

Use the deterministic red-PNG gate for the vision route:

```sh
/Volumes/minikun/homelab/tinygrad/.venv/bin/python /Volumes/minikun/homelab/java/script/tinygrad/vision-smoke.py \
  --verify-tinygrad-bypass \
  --output /tmp/minikun-vision.json
```

The Ollama-path baseline answered `สีแดงครับ` in 24.34 seconds with 5,558
prompt tokens and 10 completion tokens. After the NV reset and application
restart with TinyGrad active, this same command must pass while TinyGrad batch
and primary request counters remain unchanged and the failover metric/log shows
`reason=vision_unsupported`.

## Parallel-serving state

- Threaded HTTP admission, bounded overload with OpenAI-compatible HTTP 429,
  interactive/normal/background priority scheduling, cancellation, scheduler
  health telemetry, and loopback-only binding are implemented.
- Socket backpressure is separated from the model turn. A slow SSE consumer no
  longer holds the model executor after GPU generation has completed.
- Gemma 4 tool definitions, assistant tool calls, tool results, and OpenAI
  streaming usage are implemented and live-tested.
- The custom Q8 GEMV kernel now preserves batch rows and leading activation
  dimensions. Numerical NV tests cover batch size 2 with independent inputs.
- `--model_contexts N` is an opt-in intermediate concurrency mode. Contexts
  share model weight buffers but own separate KV/JIT state; when `N > 1`, the
  priority scheduler can yield the GPU executor between decode steps. Keep
  the target-host launch uses one 32K primary and one 4K worker context.
- Two full 32K contexts do not fit the target 16 GB NV device: the second
  warmup failed at 15.03 GB used while requesting another 429.5 MB allocation.
  The heterogeneous experiment therefore keeps one 32K primary context and
  uses `MINIKUN_TINYGRAD_WORKER_MAX_CONTEXT=4096` for an optional short-task
  worker. A full-size N=2 configuration is rejected for this host.

Measured on the target host with Gemma 4 E4B Q8_0:

| Mode | Startup | Process RSS | Result |
| --- | ---: | ---: | --- |
| one 32K context | 49.1 s | 704 MiB | 128-token decode 37 tok/s, 4.91 s wall |
| 32K + 4K contexts | 88.6 s | 848 MiB | two 128-token requests both completed in 9.59 s |
| two 32K contexts | failed | — | NV OOM at 15.03 GB used |
| one 4K batch context, batch=2 | — | — | 256 tokens in 5.58 s, 45.86 tok/s aggregate |
| one 4K batch context, batch=4 | — | — | 512 tokens in 7.87 s, 65.10 tok/s aggregate |

In the priority test, a background 128-token request started first. An
interactive request submitted one second later completed in 1.09 s, while the
background request completed in 5.60 s. The single-context background baseline
was 4.91 s. This mode is selected for interactive latency and preemption, not
for higher aggregate decode throughput; the GPU remains near 37 tok/s total.
Cancelled streaming released both the scheduler turn and model context within
one second (`in_use=0`, `queued=0`).

- The model layer now has an experimental fixed-cohort batch primitive. It
  left-pads independently sized prompts, applies per-sequence logical RoPE
  positions and padding masks, and keeps KV rows isolated. Unit tests compare
  variable-length batched output against independent contexts. The reusable
  `--batch_benchmark SIZE --benchmark COUNT --warmup` path produced 45.86 tok/s
  aggregate at batch=2 and 65.10 tok/s at batch=4, versus the 37 tok/s baseline.
  Batch=4 is therefore the current throughput candidate.
- A priority-aware HTTP cohort dispatcher now routes eligible greedy short
  requests into the batch primitive. It tracks EOS, max-token finish reasons,
  cancellation and errors per request, pads partial cohorts, yields the global
  GPU scheduler between model steps, and exposes batch metrics in `/v1/health`.
- The combined 32K primary + 4K batch=4 configuration passed startup/warmup on
  the target 16 GB NV device. Four live 128-token HTTP requests completed 512
  output tokens in 9.83 seconds (52.10 tok/s aggregate), all with isolated
  output, usage and `finish_reason=length`. A four-row streaming disconnect
  test released the active batch within two seconds (`cancelled=4`, failures=0).
- Continuous short-prompt slot refill is implemented. A reused batch row moves
  its physical `sequence_start` forward, masking all KV entries owned by the
  prior request while the other rows retain their logical RoPE positions. New
  prompts are consumed one token per active decode step, so refill is limited
  to `MINIKUN_TINYGRAD_BATCH_REFILL_MAX_PROMPT=64`. Longer work stays queued for
  the next chunk-prefilled cohort. If all decode rows finish before a refill
  prompt is consumed, the unexposed request is safely requeued and restarted;
  no partial response has reached the client.
- A staggered live test started a one-token request beside a 128-token request,
  then submitted a third request as soon as the short slot completed. The
  third request returned the isolated `REFILL` output in 1.49 seconds while the
  long row continued, with `refilled_slots=1`, `refill_aborts=0` and
  `failures=0`. Production activation still requires Mini-kun end-to-end
  quality and soak gates to pass after the TinyGrad patch is committed and
  launched from a clean worktree.
- Prompts over `MINIKUN_TINYGRAD_BATCH_MAX_PROMPT=512` bypass the padded batch
  context and use the 32K primary context. This prevents a single long MCS
  request from paying four-row padded prefill cost; `/v1/health` exposes the
  active threshold as `batcher.max_prompt_tokens`.
- Four concurrent live tool requests were normalized from Gemma's
  arguments-only `<tool_call>` dialect into the configured function name. A
  complete assistant tool-call -> tool-result -> final-answer round trip also
  passed for all four batch rows without cross-request leakage or repeated
  calls.
- The opt-in `TinyGradLiveIntegrationTest` now exercises the real Java
  `HttpTinyGradClient` against the local runtime. Four concurrent synchronous
  calls formed one full cohort, a separate SSE call returned streamed content
  and usage, and the post-test health counters remained at zero cancellations
  and zero failures. The requests deliberately used the global temperature
  `0.7`, proving the TinyGrad-only greedy override reaches the batched runtime.

The launcher now defaults to the live-tested candidate:

```sh
MINIKUN_TINYGRAD_MODEL_CONTEXTS=1 \
MINIKUN_TINYGRAD_BATCH_SIZE=4 \
MINIKUN_TINYGRAD_BATCH_MAX_CONTEXT=4096 \
MINIKUN_TINYGRAD_BATCH_WAIT_MS=5 \
MINIKUN_TINYGRAD_BATCH_REFILL_MAX_PROMPT=64 \
MINIKUN_TINYGRAD_BATCH_MAX_PROMPT=512 \
/Volumes/minikun/homelab/java/script/start-tinygrad.sh
```

Set `MINIKUN_TINYGRAD_BATCH_SIZE=0` to disable cohort batching for diagnosis.
The launcher still refuses a dirty TinyGrad worktree unless
`MINIKUN_TINYGRAD_ALLOW_DIRTY=1`; production activation must pin the committed
fork revision instead of using that development override.

`start-tinygrad.sh` already enforces the exact semantic serving topology
before reporting success. To probe an independently managed server again:

```sh
/Volumes/minikun/homelab/tinygrad/.venv/bin/python \
  /Volumes/minikun/homelab/java/script/tinygrad/tinygrad-readiness.py \
  --wait-seconds 180 \
  --output /tmp/tinygrad-readiness.json
```

The gate checks `/v1/health` and `/v1/models`, the expected Gemma 4 E4B model,
one primary context, batch size 4, refill limit 64, long-prompt threshold 512,
an `NV` allocator device, a process allocator peak no higher than 15 GiB, and
zero startup queues, waiters, or batch failures. Minikun also exposes a
semantic `tinyGradHealthIndicator` through Actuator. TinyGrad is considered a
required dependency whenever chat, task, or title provider is configured as
TinyGrad; otherwise an unavailable standby is reported without taking the
application health down.

After all live probes have written their JSON reports, make the activation
decision with one fail-closed gate:

```sh
/Volumes/minikun/homelab/tinygrad/.venv/bin/python \
  /Volumes/minikun/homelab/java/script/tinygrad/tinygrad-rollout-gate.py \
  --readiness /tmp/tinygrad-readiness.json \
  --quality-fast /tmp/tinygrad-quality-fast.json \
  --quality-long /tmp/tinygrad-quality-long.json \
  --embedding-direct /tmp/minikun-embedding-ollama.json \
  --embedding-minikun /tmp/minikun-embedding-openai.json \
  --vision /tmp/minikun-vision.json \
  --soak /tmp/tinygrad-soak.json \
  --output /tmp/tinygrad-rollout-decision.json
```

Its production defaults require all 9 fast and the 1 long-context quality
cases, verified TinyGrad bypass for vision, a two-hour soak, at least 25 output
tok/s aggregate, p95 TTFT at most 5 seconds, p95 short latency at most 3
seconds, exercised cancellation and continuous refill, no inference/refill
failures, and fully drained queues/contexts at the end. Missing or partial
reports fail the decision rather than being treated as skipped evidence.

The combined batch runtime intentionally accepts greedy decoding only. Mini-kun
defaults `MINIKUN_TINYGRAD_FORCE_GREEDY=true`, overriding its global generation
temperature only for TinyGrad requests. A direct non-zero-temperature request
receives retryable HTTP 503 `sampling_unavailable`, so the existing provider
failover can use Ollama rather than triggering a 20+ second sampling JIT compile
or an NV device fault. Ollama and other providers retain their configured
temperature.

## Long-prompt prefill safety finding

The target GGUF declares 42 transformer blocks and
`gemma4.attention.shared_kv_layers=18`. Those final 18 blocks read KV from a
matching earlier source layer, but TinyGrad previously allocated their own
full, unused cache in `_init_state`. The fork now initializes only the source
cache for shared-KV blocks. This does not change attention math or the batched
JIT signature; CPU numerical/server tests pass. With HALF KV, one 32K primary
row and the batch=4/4K context, the eliminated buffers total about 2,016 MiB
(1,344 MiB primary plus 672 MiB batch). Confirm the estimate by comparing
`allocator_memory.devices.NV.peak_bytes` from equivalent clean-process launches
before using the headroom for a larger batch or another context. Restarting
`cuda-nvcc-persistent` does not reset NV state because that container owns no
GPU device; restart the host TinyGrad process, and reset the host/eGPU only if
the direct NV backend remains faulted.

The first live patched launch passed semantic readiness with one 32K primary
context and batch=4/4K. NV allocator memory was 13.564 GiB current and 14.584
GiB process peak after warmup. The Java live integration then completed four
concurrent synchronous requests plus one streaming request as two cohorts with
zero failures, zero queued work after drain, and no increase in allocator peak.
This proves the candidate topology fits and remains stable for the smoke; it
does not by itself prove the exact 2,016 MiB delta because an equivalent
pre-patch clean-process measurement is not available.

Keep `LLM_PREFILL_CHUNK=128` on the target 16 GB NV device. A controlled launch
with chunk 256 faulted during the batch=4 warmup and left the direct NV backend
in an error state that requires a host/GPU reset; chunk 512 was therefore not
attempted. This is a hard safety result, not a candidate for production retry.

The fork contains an opt-in `Q8_GEMV_MAX_ROWS` routing gate. A value of 4 keeps
the custom Q8 GEMV kernel for single-row and four-row autoregressive decode but
routes larger prefill activations through TinyGrad's regular linear/matmul path.
The historical unlimited behavior remains the default at value 0, and unit
tests cover unlimited, decode, and prefill routing. Live performance, output
equivalence, batch=4 warmup, and memory safety must all pass after the NV reset
before the launcher may set this variable. Until then it is experimental and
the production launch configuration remains unchanged.

## Mixed-workload soak

Run the stdlib-only soak client through the TinyGrad repository environment:

```sh
/Volumes/minikun/homelab/tinygrad/.venv/bin/python \
  /Volumes/minikun/homelab/java/script/tinygrad/tinygrad-soak.py \
  --duration 300 \
  --concurrency 4 \
  --cancel-every 50 \
  --output /tmp/tinygrad-soak.json
```

It streams every request and records latency/TTFT mean and p50/p95/p99,
completion-token throughput, workload-specific percentiles, HTTP status and
error samples. A health monitor captures maximum scheduler/batch queue depth,
active batch rows and counter deltas for cohorts, refills, aborts,
cancellations and failures. Every cancellation probe uses a deliberately long
request so closing the SSE connection exercises server-side cleanup rather
than racing a naturally completed short response. The command exits non-zero
when there are no successful requests or the configurable error-rate gate is
exceeded.

The first mixed smoke completed 21 requests plus two client cancellations with
zero errors: 577 completion tokens in 20.37 seconds (28.33 tok/s aggregate),
p95 TTFT 3.75 seconds, p95 short-request latency 2.03 seconds, 17 continuous
slot refills, zero refill aborts and zero inference failures. A separate
cancellation-focused run completed 16 requests and cancelled three long
streams; the server cancellation counter increased by exactly three, all
slots were released and failures remained zero. These are harness validation
runs, not substitutes for the multi-hour production soak gate.

For the production evidence consumed by `tinygrad-rollout-gate.py`, change
`--duration` to `7200`; keep concurrency and cancellation probes enabled.

The priority scheduler deliberately permits one active model executor at a
time. A batch cohort occupies that executor for one model step, then yields so
an eligible higher-priority primary request can run. The current refill path
still consumes one shared physical KV column per step. A future paged KV layout
can reclaim that unused capacity, but must preserve the scheduling boundary,
per-row sequence masks and logical RoPE positions proven here.
