package com.minikun.commands;

import java.util.Objects;

public record CommandDescriptor(
        CommandType type,
        String name,
        String description,
        boolean visible) {

    public CommandDescriptor {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (!name.startsWith("/") || name.length() == 1) {
            throw new IllegalArgumentException("command name must be a slash command");
        }
    }
}
