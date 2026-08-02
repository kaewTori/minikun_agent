package com.minikun.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
