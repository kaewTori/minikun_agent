package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.commands.CommandCatalog;
import com.minikun.commands.CommandFormatter;
import com.minikun.commands.CommandType;
import com.minikun.diagnostics.DiagnosticsFormatter;
import com.minikun.diagnostics.DiagnosticsPrompt;
import com.minikun.diagnostics.DiagnosticsPromptBuilder;
import com.minikun.diagnostics.DiagnosticsService;
import com.minikun.diagnostics.DiagnosticsSummary;
import com.minikun.runtime.CacheFormatter;
import com.minikun.runtime.CacheService;
import com.minikun.runtime.ModelsFormatter;
import com.minikun.runtime.ModelsService;
import com.minikun.runtime.VersionFormatter;
import com.minikun.runtime.VersionService;

/** Handles deterministic runtime commands and the optional conversational diagnostics route. */
final class ChatCommandHandler {
    private final CommandCatalog commandCatalog;
    private final CommandFormatter commandFormatter;
    private final DiagnosticsService diagnosticsService;
    private final DiagnosticsFormatter diagnosticsFormatter;
    private final DiagnosticsPromptBuilder diagnosticsPromptBuilder;
    private final VersionService versionService;
    private final VersionFormatter versionFormatter;
    private final ModelsService modelsService;
    private final ModelsFormatter modelsFormatter;
    private final CacheService cacheService;
    private final CacheFormatter cacheFormatter;
    private final SpringAiPromptAdapter promptAdapter;
    private final OpenAiChatResponseFactory responseFactory;

    ChatCommandHandler(
            CommandCatalog commandCatalog,
            CommandFormatter commandFormatter,
            DiagnosticsService diagnosticsService,
            DiagnosticsFormatter diagnosticsFormatter,
            DiagnosticsPromptBuilder diagnosticsPromptBuilder,
            VersionService versionService,
            VersionFormatter versionFormatter,
            ModelsService modelsService,
            ModelsFormatter modelsFormatter,
            CacheService cacheService,
            CacheFormatter cacheFormatter,
            SpringAiPromptAdapter promptAdapter,
            OpenAiChatResponseFactory responseFactory) {
        this.commandCatalog = commandCatalog;
        this.commandFormatter = commandFormatter;
        this.diagnosticsService = diagnosticsService;
        this.diagnosticsFormatter = diagnosticsFormatter;
        this.diagnosticsPromptBuilder = diagnosticsPromptBuilder;
        this.versionService = versionService;
        this.versionFormatter = versionFormatter;
        this.modelsService = modelsService;
        this.modelsFormatter = modelsFormatter;
        this.cacheService = cacheService;
        this.cacheFormatter = cacheFormatter;
        this.promptAdapter = promptAdapter;
        this.responseFactory = responseFactory;
    }

    ChatCompletionResponse blocking(
            ChatMessage userMessage,
            String publicModel,
            boolean conversationalDiagnostics,
            ChatModelGateway modelGateway) {
        var command = commandCatalog.findExact(userMessage.content());
        if (command.isEmpty()) {
            return null;
        }
        if (command.get().type() == CommandType.DIAGNOSTICS && conversationalDiagnostics) {
            return conversationalDiagnostics(userMessage, publicModel, modelGateway);
        }
        String content = content(userMessage);
        return content == null ? null : responseFactory.contentCompletion(publicModel, content);
    }

    String content(ChatMessage userMessage) {
        return commandCatalog.findExact(userMessage.content())
                .map(command -> switch (command.type()) {
                    case DIAGNOSTICS -> diagnosticsFormatter.format(diagnosticsService.summarize());
                    case HELP -> commandFormatter.format(commandCatalog);
                    case VERSION -> versionFormatter.format(versionService.snapshot());
                    case MODELS -> modelsFormatter.format(modelsService.snapshot());
                    case CACHE -> cacheFormatter.format(cacheService.snapshot());
                })
                .orElse(null);
    }

    private ChatCompletionResponse conversationalDiagnostics(
            ChatMessage userMessage,
            String publicModel,
            ChatModelGateway modelGateway) {
        DiagnosticsSummary summary = diagnosticsService.summarize();
        try {
            DiagnosticsPrompt diagnosticsPrompt = diagnosticsPromptBuilder.build(summary, userMessage.content());
            String content = modelGateway.chat(
                    promptAdapter.diagnostics(diagnosticsPrompt, diagnosticsFormatter),
                    "chat_model",
                    null,
                    null)
                    .getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Diagnostics LLM returned empty content");
            }
            return responseFactory.contentCompletion(publicModel, content);
        } catch (RuntimeException exception) {
            return responseFactory.contentCompletion(publicModel, diagnosticsFormatter.format(summary));
        }
    }
}
