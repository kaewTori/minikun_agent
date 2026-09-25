# Personal Context Runtime

`PersonalContextRuntime` is the boundary between chat orchestration and the
context/token pipeline used by the personal agent.

```text
PromptRequest
    -> character/context selection
    -> character-budgeted prompt
    -> token budget and dynamic max-tokens
    -> bounded recovery and re-composition
    -> model prompt
```

The runtime currently performs one bounded recovery pass. It reduces the
character context budget to 75% or the measured token-fit ratio, whichever is
smaller, when the token-pressure analyzer reports a warning or critical state.
Token estimates give non-ASCII text a larger allowance than ASCII and account
for code/JSON punctuation, calibrated against the configured local model.
Required prompt sections can borrow unused character quotas before optional
memory or knowledge is evicted. Recent role messages use remaining total prompt
space after rendered system context and the current user message.

Future context sources such as memory, search, browser, and tools should enter
through `PromptRequest`/context items rather than adding orchestration branches
to `ChatService`.

Each preparation also emits a `ContextRuntimeSnapshot`. It records final prompt
size, estimated input/output budget, recovery attempts, and the character
contribution of sources that are directly available at composition time.
