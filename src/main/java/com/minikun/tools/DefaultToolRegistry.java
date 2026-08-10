package com.minikun.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public final class DefaultToolRegistry implements ToolRegistry {
    private final Map<String, Tool> tools;

    public DefaultToolRegistry(List<Tool> registeredTools) {
        Objects.requireNonNull(registeredTools, "registered tools must not be null");
        Map<String, Tool> orderedTools = new LinkedHashMap<>();
        registeredTools.stream()
                .sorted((left, right) -> left.definition().name().compareTo(right.definition().name()))
                .forEach(tool -> {
                    Objects.requireNonNull(tool, "registered tool must not be null");
                    String name = tool.definition().name();
                    if (orderedTools.putIfAbsent(name, tool) != null) {
                        throw new IllegalArgumentException("duplicate tool name: " + name);
                    }
                });
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(orderedTools));
    }

    @Override
    public Optional<Tool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    @Override
    public Collection<ToolDefinition> definitions() {
        return List.copyOf(tools.values().stream().map(Tool::definition).toList());
    }
}