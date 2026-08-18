package org.springframework.boot.health.actuate.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.tools.ServiceHealthTool;
import com.minikun.tools.ToolErrorCode;
import com.minikun.tools.ToolResult;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.ApiVersion;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class ServiceHealthToolTest {
    @Test
    void returnsOverallAndComponentStatusesWithoutHealthDetails() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        HealthDescriptor database = new IndicatedHealthDescriptor(Health.up().withDetail("secret", "hidden").build());
        CompositeHealthDescriptor overall = new CompositeHealthDescriptor(
                ApiVersion.V3, Status.UP, Map.of("db", database));

        when(endpoint.health()).thenReturn(overall);

        ToolResult result = new ServiceHealthTool(endpoint).execute(null, Map.of());

        assertTrue(result.success());
        assertEquals(Map.of(
                "status", "UP",
                "healthy", true,
                "components", Map.of("db", Map.of("status", "UP", "healthy", true))), result.value());
    }

    @Test
    void marksDownStatusAsUnhealthy() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        HealthDescriptor overall = new IndicatedHealthDescriptor(Health.down().build());

        when(endpoint.health()).thenReturn(overall);

        ToolResult result = new ServiceHealthTool(endpoint).execute(null, Map.of());

        assertTrue(result.success());
        assertEquals("DOWN", ((Map<?, ?>) result.value()).get("status"));
        assertFalse((Boolean) ((Map<?, ?>) result.value()).get("healthy"));
    }

    @Test
    void doesNotExposeActuatorFailureDetails() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenThrow(new IllegalStateException("database password"));

        ToolResult result = new ServiceHealthTool(endpoint).execute(null, Map.of());

        assertFalse(result.success());
        assertEquals(ToolErrorCode.EXECUTION_FAILED, result.errorCode());
        assertEquals("service health is temporarily unavailable", result.error());
    }
}
