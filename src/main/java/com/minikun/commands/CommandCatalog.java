package com.minikun.commands;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public final class CommandCatalog {

    private final List<CommandDescriptor> commands = List.of(
            new CommandDescriptor(CommandType.DIAGNOSTICS, "/diagnostics", "Show current Search runtime metrics", true),
            new CommandDescriptor(CommandType.HELP, "/help", "Show supported slash commands", true),
            new CommandDescriptor(CommandType.VERSION, "/version", "Show application and runtime versions", true),
            new CommandDescriptor(CommandType.MODELS, "/models", "Show configured model information", true),
            new CommandDescriptor(CommandType.CACHE, "/cache", "Show configured cache information", true));
    private final Map<String, CommandDescriptor> commandsByName = commands.stream()
            .collect(Collectors.toUnmodifiableMap(CommandDescriptor::name, Function.identity()));

    public List<CommandDescriptor> commands() {
        return commands;
    }

    public Optional<CommandDescriptor> findExact(String input) {
        return Optional.ofNullable(commandsByName.get(input));
    }
}
