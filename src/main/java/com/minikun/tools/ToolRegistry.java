package com.minikun.tools;

import java.util.Collection;
import java.util.Optional;

public interface ToolRegistry {
    Optional<Tool> find(String name);

    Collection<ToolDefinition> definitions();
}