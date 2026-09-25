# Conversation Memory

The Java service uses Spring AI `ChatMemory` for short-term conversation history and PostgreSQL-backed application memory for persisted knowledge. There is no separate memory service in the request path.

## Application boundary

Conversation identity belongs to the application boundary. `ConversationId` is an application-level identifier that remains stable across transport protocols and LLM providers.

`ConversationIdResolver` resolves the identifier once at the OpenAI-compatible HTTP boundary. It checks, in order:

1. `X-Conversation-Id`.
2. The application request field `conversation_id`.
3. Transport identifiers such as `X-OpenWebUI-Chat-Id` or `X-Chat-Id`.
4. A newly generated UUID when no identifier is supplied.

The resolved value is returned as `X-Conversation-Id`, and its origin is returned as `X-Conversation-Id-Source`. Both headers are exposed through `Access-Control-Expose-Headers`, so browser clients can read and persist them. Clients must reuse the returned identifier for every request in the same chat. Identifiers longer than the 36-character Spring AI JDBC limit are deterministically mapped to a UUID, so repeatedly supplying the same transport identifier remains stable.

`ConversationMemoryService` is the only application service boundary that translates between application messages and Spring AI messages.

Its public API is:

- `load(ConversationId)` returns application `ChatMessage` values.
- `append(ConversationId, ChatMessage)` stores one application message.
- `appendTurn(ConversationId, user, assistant)` stores a completed turn through one ChatMemory write boundary.
- `clear(ConversationId)` removes the conversation history.

Callers use `ConversationId` and `ChatMessage`; Spring AI message types must not leak through this boundary. The service supports `user`, `assistant`, and `system` messages. Tool messages are currently rejected.

## Request lifecycle

For a non-streaming chat request:

1. Resolve `ConversationId` at the application boundary.
2. Load existing history.
3. Build the PCS prompt from existing history and the request-local user message.
4. Convert the PCS prompt to Spring AI messages in `ChatService`.
5. Call the chat model.
6. Persist the user and assistant messages together only after successful completion.

Streaming requests follow the same ordering. Assistant chunks are accumulated and the completed user/assistant turn is persisted only when the stream completes successfully. Model failures and cancelled streams therefore leave no orphan user message. The prompt window also ignores trailing legacy user messages that were persisted by older releases without an assistant response.

Before either model call, `ChatService` asks `MemoryRecallService` for long-term knowledge using the explicit request `owner_id`. Long-term recall is intentionally cross-conversation but remains owner-scoped; absent owner identity fails closed with an empty memory context. The JDBC repository applies the owner predicate, deterministic recent-first ordering, and `LIMIT` before returning rows. `minikun.memory.retrieval.max-candidates` is the application-owned retrieval bound and is separate from formatter character budgets and Knowledge Selection limits.

Recall failures are handled by `ChatService`: a warning is logged and the request continues without a Knowledge section. Owner-less legacy rows remain stored but are excluded from long-term owner-scoped recall; they are never assigned an owner automatically. Retrieval uses embedding similarity when an `EmbeddingModel` is available and `MINIKUN_MEMORY_SEMANTIC_ENABLED=true`, then falls back to deterministic local relevance when embeddings are unavailable or fail.

Each persisted long-term memory also retains the extraction `confidence` and `reason`. Recall includes both fields in each Knowledge entry so the model can distinguish the remembered fact from the evidence for keeping it. Existing rows created before these columns existed receive the migration defaults `confidence=0.0` and `reason=legacy persisted memory`.

Keeping the current user message request-local until completion prevents it from appearing twice and prevents incomplete turns from entering persisted history.

## Storage and windowing

Production configuration uses Spring AI JDBC ChatMemory with PostgreSQL:

- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `spring.ai.chat.memory.repository.jdbc.initialize-schema`
- `spring.ai.chat.memory.max-messages`

The default maximum is 20 messages and can be overridden with `SPRING_AI_CHAT_MEMORY_MAX_MESSAGES`. `MessageWindowChatMemory` applies the configured window so old short-term messages are evicted as new messages are added.

## Prompt continuity

When a client sends the visible transcript in the OpenAI-compatible `messages` array, the service reconciles it with stored JDBC history. Minikun Cockpit sends its completed visible user/assistant transcript on every turn, including the current user message, so assistant answers remain available even when the browser and server memory are being reconciled. A recent client suffix recovers its older stored turns, overlapping histories are merged without duplicates, and a genuinely divergent visible transcript remains authoritative so an edited branch cannot pull in unrelated stored messages. Stored JDBC history is also used when clients send only the current user message. Recent turns are rendered as native `USER` and `ASSISTANT` prompt messages; rolling summary and omission notes remain system context. System, internal command, and client-local error messages are excluded.

Cockpit stores attachment assets locally in IndexedDB instead of syncing raw Base64 data. Text-file bodies can be restored on later turns, and up to two recent image attachments are reattached to the current multimodal request so follow-up questions can still refer to them. Editing a user turn creates a new branch, while regenerating the newest assistant response replaces the truncated transcript in sync storage.

The rolling summary uses the weighted conversation section (30 of 115 shares). Recent user/assistant messages can use the space left in the total prompt after system context and the current user message. The normal total is 24,000 characters; creative writing and storytelling turns use 40,000 characters by default. History is selected from the newest message backwards and an omission marker is added when older messages do not fit, including when the final prompt renderer trims them. Context-pressure recovery can reduce the total again. Verified tool-result turns retain recent conversation alongside the current tool evidence.

Creative prompts instruct the model to reserve space for a deliberate scene or chapter ending. If the provider still returns `finish_reason=length`, the service performs one bounded continuation using the original request, compact identity and recent-conversation context, and the tail of the interrupted draft. Exact overlap at the join is removed before returning or persisting the combined answer. Streaming clients receive the continuation in the same response before the final stop chunk; ordinary non-length-limited turns do not pay for an additional model call.

Once stored history reaches 10 user/assistant messages, turns older than the newest 8 messages are merged into a persistent rolling summary. The summary is scoped by both `owner_id` and `conversation_id`, stored in `minikun_conversation_summary`, and injected before the recent verbatim turns. Recent turns are explicitly authoritative when they conflict with older summary content. Message fingerprints make the update incremental, so an older turn already represented in the summary is not summarized again.

If the background summary has not yet covered newly aged-out turns, the recent window grows to include those turns until the summary catches up. The summary snapshot and its coverage are loaded together, so this does not add a second storage read to the request path.

If the client supplies a visible transcript that diverges from stored history, prompt composition omits the stored rolling summary and uses the visible transcript's full conversation budget.

Summary generation runs on a bounded single-worker queue after a successful turn is persisted. It uses the configured task model and is deliberately outside the response path: model, queue, or storage failures are logged but do not fail or delay the user's completed chat response. Until the first summary exists, prompt composition continues to use the largest recent history window that fits the normal conversation budget.

The rolling-summary behavior can be tuned with `MINIKUN_CONVERSATION_SUMMARY_ENABLED`, `MINIKUN_CONVERSATION_SUMMARY_MINIMUM_HISTORY_MESSAGES`, `MINIKUN_CONVERSATION_SUMMARY_RETAIN_RECENT_MESSAGES`, `MINIKUN_CONVERSATION_SUMMARY_MAX_CHARACTERS`, `MINIKUN_CONVERSATION_SUMMARY_MAX_OUTPUT_TOKENS`, `MINIKUN_CONVERSATION_SUMMARY_MAX_TRACKED_FINGERPRINTS`, and `MINIKUN_CONVERSATION_SUMMARY_QUEUE_CAPACITY`.

## Operations

Owner-scoped summary inspection and repair use the existing memory-management trust boundary:

- `GET /v1/conversations/{conversationId}/summary?ownerId=default`
- `POST /v1/conversations/{conversationId}/summary/rebuild?ownerId=default`
- `DELETE /v1/conversations/{conversationId}/summary?ownerId=default`
- `DELETE /v1/conversations/{conversationId}?ownerId=default`

The conversation delete endpoint clears short-term JDBC chat memory and its rolling summary but intentionally leaves long-term memory intact. When configured, send `X-Minikun-Memory-Token`; the dedicated override is `MINIKUN_CONVERSATION_SUMMARY_MANAGEMENT_TOKEN`. Status includes enabled/present state, summary age, summarized-message count, retained history count, and whether an update is pending. Cockpit sync also persists conversation pin/archive state and bounded per-message branch, status, feedback, and source metadata.

Actuator publishes `minikun.conversation.summary.jobs` with `updated`, `noop`, `failed`, `coalesced`, and `queue_full` outcomes, plus `minikun.conversation.summary.duration` and `minikun.conversation.summary.queue.depth`.

`deploy/migrate-database.sh` applies files in `deploy/migrations` exactly once through `minikun_schema_migration`. The normal deployment script runs it before replacing and restarting the application. The PostgreSQL round-trip test can be run against a disposable or dedicated database with `MINIKUN_POSTGRES_INTEGRATION=true` and the standard datasource environment variables.

The current schema setting remains `always` for local bootstrap compatibility. Production deployment also runs the explicit versioned migration before application restart; once every legacy schema has been moved under the migration runner, automatic SQL initialization can be disabled globally.

Tests replace JDBC memory with an in-memory `MessageWindowChatMemory`, so unit and application-context tests do not require a running PostgreSQL instance.
