# Conversation Memory

The Java service uses Spring AI `ChatMemory` for short-term conversation history. The Rust memory service remains a separate system for future long-term or semantic memory and is not part of this request path.

## Application boundary

Conversation identity belongs to the application boundary. `ConversationId` is an application-level identifier that remains stable across transport protocols and LLM providers.

`ConversationIdResolver` resolves the identifier once at the OpenAI-compatible HTTP boundary. It checks, in order:

1. `X-Conversation-Id` or the legacy application request field `conversation_id`.
2. Transport identifiers such as `X-OpenWebUI-Chat-Id` or `X-Chat-Id`.
3. A newly generated UUID when no identifier is supplied.

The resolved value is returned as `X-Conversation-Id`, allowing clients to reuse it. Downstream services receive only the resolved `ConversationId`; they do not inspect HTTP headers, OpenWebUI identifiers, or generate conversation IDs.

`ConversationMemoryService` is the only application service boundary that translates between application messages and Spring AI messages.

Its public API is:

- `load(ConversationId)` returns application `ChatMessage` values.
- `append(ConversationId, ChatMessage)` stores one application message.
- `clear(ConversationId)` removes the conversation history.

Callers use `ConversationId` and `ChatMessage`; Spring AI message types must not leak through this boundary. The service supports `user`, `assistant`, and `system` messages. Tool messages are currently rejected.

## Request lifecycle

For a non-streaming chat request:

1. Resolve `ConversationId` at the application boundary.
2. Load existing history.
3. Append the current user message to memory.
4. Build the PCS prompt from the history loaded before the append.
5. Convert the PCS prompt to Spring AI messages in `ChatService`.
6. Call the chat model.
7. Append the assistant response after a successful completion.

Streaming requests follow the same history and user-message ordering. Assistant chunks are accumulated and appended once when the stream completes successfully.

Before either model call, `ChatService` asks `MemoryRecallService` for long-term knowledge. The recall path reads persisted memories from PostgreSQL, applies deterministic recent-first ordering and a maximum count in `MemorySelector`, and converts the selected records to a bounded `KnowledgeContext` through `MemoryFormatter`. It does not use an LLM, embeddings, semantic search, or vector storage. Recall covers all categories and all conversations in this version; `conversation_id` remains persistence provenance rather than a recall filter.

Recall failures are handled by `ChatService`: a warning is logged and the request continues without a Knowledge section. `MemoryRepository` only retrieves persisted rows; it does not apply selection rules. The KnowledgeContext character budget and maximum memory count are configurable with `MINIKUN_MEMORY_RECALL_MAXIMUM_CHARACTERS` and `MINIKUN_MEMORY_RECALL_MAXIMUM_COUNT`.

Each persisted long-term memory also retains the extraction `confidence` and `reason`. Recall includes both fields in each Knowledge entry so the model can distinguish the remembered fact from the evidence for keeping it. Existing rows created before these columns existed receive the migration defaults `confidence=0.0` and `reason=legacy persisted memory`.

Loading history before appending the current user message prevents the current message from appearing twice in the composed conversation section and as the separate PCS `USER` message.

## Storage and windowing

Production configuration uses Spring AI JDBC ChatMemory with PostgreSQL:

- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `spring.ai.chat.memory.repository.jdbc.initialize-schema`
- `spring.ai.chat.memory.max-messages`

The default maximum is 20 messages and can be overridden with `SPRING_AI_CHAT_MEMORY_MAX_MESSAGES`. `MessageWindowChatMemory` applies the configured window so old short-term messages are evicted as new messages are added.

The current schema setting is `always`, which is convenient for local setup. A production deployment should use an explicit migration process and change schema initialization policy accordingly.

Tests replace JDBC memory with an in-memory `MessageWindowChatMemory`, so unit and application-context tests do not require a running PostgreSQL instance.
