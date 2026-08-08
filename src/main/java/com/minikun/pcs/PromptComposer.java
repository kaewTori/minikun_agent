package com.minikun.pcs;

import com.minikun.pcs.model.UserMessage;
import com.minikun.pcs.model.Prompt;

public final class PromptComposer {
    private final McsSelector selector;
    private final SelectionContextFactory contextFactory;

    public PromptComposer() {
        this(new McsSelector());
    }

    public PromptComposer(McsSelector selector) {
        this(selector, new SelectionContextFactory());
    }

    public PromptComposer(McsSelector selector, SelectionContextFactory contextFactory) {
        this.selector = java.util.Objects.requireNonNull(selector, "selector");
        this.contextFactory = java.util.Objects.requireNonNull(contextFactory, "contextFactory");
    }

    public Prompt compose(PromptRequest request) {
        return composeWithDiagnostics(request).prompt();
    }

    public PromptCompositionResult composeWithDiagnostics(PromptRequest request) {
        if (request == null) {
            throw new PromptException("request must not be null");
        }
        if (request.character() == null) {
            throw new PromptException("character must not be null");
        }
        if (request.runtime() == null || isBlank(request.runtime().content())) {
            throw new PromptException("runtime must not be blank");
        }
        UserMessage userMessage = request.userMessage();
        if (userMessage == null || isBlank(userMessage.content())) {
            throw new PromptException("user message must not be blank");
        }
        McsSelectionContext context = contextFactory.create(
            userMessage.content(),
            request.conversation() == null ? "" : request.conversation().content(),
            request.searchSelectionSignals(),
            request.searchContext());
        McsSelectionResult selection = selector.select(request.character(), context);
        return new PromptCompositionResult(
            PromptRenderer.render(request, selection.selectedModules()), selection.decisions());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}