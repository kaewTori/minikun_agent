# Memory Runtime M1–M7

The memory runtime now keeps the following boundaries explicit:

```text
conversation memory: conversationId
long-term memory:    ownerId
request lifecycle:   Observation / MinikunEvent
```

Long-term recall uses `LongTermMemoryScope` and a lightweight local relevance
ranker. It scores query term overlap and confidence, then uses recency as a
tie-breaker. This is intentionally inexpensive for a single-user home agent;
embedding retrieval can be added behind the same recall boundary later.

Reflection is deferred through a bounded single-thread executor when the Spring
runtime is available. If the queue is full, the existing synchronous path is used
as a safe fallback. Reflection failures never fail the chat response.

Observations are immutable request-local facts. They are not long-term memories,
and the publisher is protected by a failure boundary. The default publisher is
no-op, so adding consumers later does not change chat behavior.

Memory knowledge candidates now carry provenance, including the originating
conversation when available. This enables future citation rendering without
changing the core knowledge contract.
