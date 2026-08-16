# Adaptive Persona Runtime

The personal-agent personality has two layers:

```text
MCS core character
  identity / values / boundaries / base communication
            +
runtime overlays
  memory / profile / preferences / search / tools / mood
```

Runtime overlays are represented by `PersonaSelectionSignals` and are deliberately
kept separate from core identity. `PersonaArbiter` applies a deterministic policy;
it never changes identity, values, or boundaries.

## Packages

| Package | Responsibility |
|---|---|
| `personality.model` | profile, preference and mood value objects |
| `personality.signal` | pure runtime signal projection |
| `personality.arbitration` | deterministic overlay selection |
| `personality.runtime` | runtime/service facade |
| `personality.profile` | profile store |
| `personality.preference` | preference store |
| `personality.diagnostic` | explainability |

`PromptRequest` carries the optional signals and `PromptComposer` propagates them
into `McsSelectionContext`. Existing constructors remain compatible and produce
empty signals.

## Current behavior

- Memory is considered available when recalled memories are present.
- Profile and preferences activate separate overlays.
- Tool calls can activate a confirmation-aware tool overlay.
- Search activates a search-aware overlay.
- Mood is an overlay only when intensity is greater than zero.
- Core identity, values and boundaries are never adaptive overlays.

The in-memory profile and preference stores are intentionally suitable for a
single-user home agent. They can later be replaced with owner-scoped persistence
without changing the runtime contract.
