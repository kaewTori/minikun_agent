# Conversation Policy and Relationship Continuity

Mini-kun now separates stable character from the response posture needed for a
specific turn.

```text
latest message + recent turns
        -> ConversationPolicyEngine
        -> intent / user need / initiative / challenge / question budget
        -> Natural conversation capability
        -> model prompt
```

The deterministic first pass recognizes `CONNECT`, `VENT`, `EXPLORE`, `DECIDE`,
`LEARN`, `CREATE`, `ACT`, and `REFLECT`. It does not replace safety checks, tool
authorization, or the MCS identity. It decides whether the response should listen,
ask one question, reflect, advise, challenge, explain, co-create, celebrate, or
execute. Recent user turns provide weak continuity for short elliptical replies.

## Relationship threads

`ConversationThread` is a durable open loop, distinct from factual long-term
memory and actionable tasks. It records a bounded topic, summary, last decision,
unresolved question, and optional check-in.

Automatic capture is deliberately conservative. A thread is created only when a
turn explicitly signals an unresolved issue or asks Mini-kun to return to the
topic. Relevant open threads are selected lexically and injected as untrusted
background; at most three enter a prompt.

Check-ins require `check_in_consent=true`. The database constraint rejects a
scheduled check-in without consent. The scheduler also uses the central proactive
quiet-hours policy and records delivery through the normal notification pipeline.

Management endpoints:

- `GET/POST /v1/personal/conversation-threads`
- `PATCH/DELETE /v1/personal/conversation-threads/{id}`

## Feedback learning

Cockpit feedback is persisted through `/v1/chat/feedback`. Free-text reasons are
mapped to a closed category such as `TOO_LONG`, `ADVICE_TOO_SOON`,
`TOO_AGREEABLE`, or `SHOULD_HAVE_ACTED`. Only safe style categories update the
existing adaptive preference system. Context mistakes and unknown reasons remain
available for evaluation but do not silently alter personality.

## Conversation modes

Explicit `COMPANION`, `WORK`, and `FOCUS` modes are owner- and conversation-scoped
and persist in PostgreSQL. Cockpit can select a mode directly. `BALANCED` clears
the override so the turn policy can adapt automatically.

## Storage

The runtime adds:

- `minikun_conversation_thread`
- `minikun_chat_feedback`
- `minikun_companion_mode`

Fresh installations use `memory-schema.sql`; existing deployments apply
`V20260828_01__relationship_chat_policy.sql`.
