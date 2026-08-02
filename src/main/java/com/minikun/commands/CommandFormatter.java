package com.minikun.commands;

import org.springframework.stereotype.Component;

@Component
public final class CommandFormatter {

    public String format(CommandCatalog catalog) {
        StringBuilder output = new StringBuilder("Supported commands\n");
        catalog.commands().stream()
                .filter(CommandDescriptor::visible)
                .forEach(command -> output.append(command.name())
                        .append(" - ").append(command.description()).append('\n'));
        return output.toString();
    }
}
