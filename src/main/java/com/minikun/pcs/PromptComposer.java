package com.minikun.pcs;

import com.minikun.pcs.model.UserMessage;
import com.minikun.pcs.model.Prompt;

import java.util.List;
import java.util.Optional;

public final class PromptComposer {
    private final McsSelector selector;
    private final SelectionContextFactory contextFactory;
    private final ContextProcessor contextProcessor;
    private final ContextItemAssembler contextItemAssembler;

    public PromptComposer() {
        this(new McsSelector());
    }

    public PromptComposer(McsSelector selector) {
        this(selector, new SelectionContextFactory(),
            new DefaultContextProcessor(new DefaultContextItemSelector(), new DefaultContextCompressor()),
            new ContextItemAssembler());
    }

    public PromptComposer(McsSelector selector, SelectionContextFactory contextFactory) {
        this(selector, contextFactory,
            new DefaultContextProcessor(new DefaultContextItemSelector(), new DefaultContextCompressor()),
            new ContextItemAssembler());
    }

    public PromptComposer(
            McsSelector selector,
            SelectionContextFactory contextFactory,
            ContextProcessor contextProcessor) {
        this(selector, contextFactory, contextProcessor, new ContextItemAssembler());
    }

    public PromptComposer(
            McsSelector selector,
            SelectionContextFactory contextFactory,
            ContextProcessor contextProcessor,
            ContextItemAssembler contextItemAssembler) {
        this.selector = java.util.Objects.requireNonNull(selector, "selector");
        this.contextFactory = java.util.Objects.requireNonNull(contextFactory, "contextFactory");
        this.contextProcessor = java.util.Objects.requireNonNull(contextProcessor, "context processor");
        this.contextItemAssembler = java.util.Objects.requireNonNull(
                contextItemAssembler, "context item assembler");
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
        Optional<ContextProcessingResult> processingResult = processContext(request, selection);
        return new PromptCompositionResult(
                PromptRenderer.render(request, selection.selectedModules(), processingResult),
                selection.decisions(), processingResult);
    }

    private Optional<ContextProcessingResult> processContext(
            PromptRequest request,
            McsSelectionResult selection) {
        if (request.contextBudget() == null) {
            return Optional.empty();
        }
        List<ContextItem> items = contextItemAssembler.assemble(request, selection.selectedModules());
        return Optional.of(contextProcessor.process(new ContextProcessingRequest(request.contextBudget(), items)));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}