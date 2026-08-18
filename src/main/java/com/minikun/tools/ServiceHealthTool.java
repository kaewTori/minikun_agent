package com.minikun.tools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/** Read-only service status backed by Spring Boot Actuator health contributors. */
@Component
public final class ServiceHealthTool implements Tool {
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "service.health",
            "Check the current Mini-kun service health using Spring Boot Actuator. "
                    + "Use this when the user asks whether the service, database, or connected components are working. "
                    + "Report only the verified status returned by this tool; do not expose internal details or secrets.",
            Map.of());

    private final HealthEndpoint healthEndpoint;

    public ServiceHealthTool(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = Objects.requireNonNull(healthEndpoint, "health endpoint must not be null");
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolCallContext context, Map<String, Object> arguments) {
        try {
            return ToolResult.success(snapshot(healthEndpoint.health()));
        } catch (RuntimeException exception) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "service health is temporarily unavailable");
        }
    }

    private Map<String, Object> snapshot(HealthDescriptor descriptor) {
        Map<String, Object> result = new LinkedHashMap<>();
        Status status = descriptor.getStatus();
        result.put("status", status.getCode());
        result.put("healthy", Status.UP.equals(status));

        Map<String, Object> components = components(descriptor);
        if (!components.isEmpty()) {
            result.put("components", components);
        }
        return result;
    }

    private Map<String, Object> components(HealthDescriptor descriptor) {
        if (!(descriptor instanceof CompositeHealthDescriptor composite)) {
            return Map.of();
        }

        Map<String, Object> components = new LinkedHashMap<>();
        composite.getComponents().forEach((name, component) -> components.put(name, snapshot(component)));
        return components;
    }
}
