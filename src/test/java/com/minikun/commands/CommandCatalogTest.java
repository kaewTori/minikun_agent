package com.minikun.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CommandCatalogTest {

    @Test
    void exposesDeterministicImmutableCommandsAndExactLookup() {
        CommandCatalog catalog = new CommandCatalog();

        assertEquals(CommandType.DIAGNOSTICS, catalog.findExact(catalog.commands().getFirst().name()).orElseThrow().type());
        assertTrue(catalog.findExact(catalog.commands().getFirst().name() + " extra").isEmpty());
        assertTrue(catalog.findExact("/unsupported").isEmpty());
        assertTrue(catalog.commands().getFirst().visible());
    }

    @Test
    void exposesRuntimeCommandsInCatalogOrderAndHelpUsesThoseDescriptors() {
        CommandCatalog catalog = new CommandCatalog();

        assertEquals(List.of(CommandType.DIAGNOSTICS, CommandType.HELP,
                CommandType.VERSION, CommandType.MODELS, CommandType.CACHE),
                catalog.commands().stream().map(CommandDescriptor::type).toList());

        String help = new CommandFormatter().format(catalog);
        catalog.commands().stream()
                .filter(CommandDescriptor::visible)
                .forEach(command -> assertTrue(help.contains(command.name())));
    }
}
