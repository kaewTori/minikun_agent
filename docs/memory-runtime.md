# Memory Runtime M1–M7

The memory runtime now keeps the following boundaries explicit:

```text
conversation memory: conversationId
long-term memory:    ownerId
request lifecycle:   Observation / MinikunEvent
```

Long-term recall uses `LongTermMemoryScope` across conversations belonging to
the same owner. When an embedding model is available, semantic similarity is
combined with memory confidence; failures fall back to the lightweight local
ranker, which scores query overlap and confidence with recency as a tie-breaker.

Reflection is deferred through a bounded single-thread executor when the Spring
runtime is available. If the queue is full, the existing synchronous path is used
as a safe fallback. Reflection failures never fail the chat response.

Observations are immutable request-local facts. They are not long-term memories,
and the publisher is protected by a failure boundary. The default publisher is
no-op, so adding consumers later does not change chat behavior.

Memory knowledge candidates now carry provenance, including the originating
conversation when available. This enables future citation rendering without
changing the core knowledge contract.
