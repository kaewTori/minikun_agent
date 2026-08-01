# Provider Composition System (PCS)

PCS v1 is a provider-neutral prompt composition library. It builds an immutable `Prompt` value that can be translated by an application adapter into a provider-specific request.

## Contract

`PromptComposer.compose(PromptRequest)` validates the required request fields and returns a `Prompt` containing an immutable list of `PromptMessage` values.

PCS v1 supports two roles:

1. `SYSTEM`: the character and runtime instructions, followed by optional conversation and knowledge sections and capability instructions.
2. `USER`: the current user message.

The renderer keeps the section order deterministic:

1. Character
2. Runtime
3. Conversation, when present
4. Knowledge, when present
5. Capabilities, when present and non-empty

Empty optional sections are omitted. The current user message is kept as its own `USER` message rather than being appended to the system content.

## Provider boundary

PCS must not import Spring AI, OpenAI, Ollama, or any other provider SDK. Provider conversion belongs to the application layer. In this service, `ChatService` maps PCS `PromptMessage` values to Spring AI `SystemMessage` and `UserMessage` instances immediately before calling the chat model.

This keeps prompt composition testable and independent from the transport or model provider. New providers should consume the PCS model through an adapter without changing PCS itself.

## Usage flow

1. Build a `PromptRequest` from the character specification, runtime context, prior conversation, optional knowledge, capabilities, and current user message.
2. Call `PromptComposer.compose(request)`.
3. Convert the returned `Prompt` to the selected provider request at the application boundary.
4. Send the provider request to the model.

PCS does not store conversation history, call a model, or select a provider.
