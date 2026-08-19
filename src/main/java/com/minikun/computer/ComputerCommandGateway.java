package com.minikun.computer;

import com.minikun.guardian.GuardianCommandRunner;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Executes only fixed local commands and allowlisted applications/workflows, without a shell. */
public final class ComputerCommandGateway {
    private final GuardianCommandRunner runner;
    private final Duration timeout;
    private final Map<String, ComputerWorkflowDefinition> workflows;
    private final Map<String, String> applications;

    public ComputerCommandGateway(
            GuardianCommandRunner runner,
            Duration timeout,
            List<ComputerWorkflowDefinition> workflows,
            Set<String> applications) {
        this.runner = runner;
        this.timeout = timeout;
        Map<String, ComputerWorkflowDefinition> configured = new LinkedHashMap<>();
        workflows.forEach(value -> configured.put(value.id(), value));
        this.workflows = Map.copyOf(configured);
        this.applications = applications.stream().filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toUnmodifiableMap(
                        value -> value.trim().toLowerCase(Locale.ROOT), String::trim, (left, right) -> left));
    }

    public String clipboard() {
        var result = runner.run(List.of("/usr/bin/pbpaste"), timeout);
        if (result.timedOut() || result.exitCode() != 0) {
            throw new IllegalStateException("clipboard is unavailable");
        }
        return result.output();
    }

    public List<Map<String, String>> workflows() {
        return workflows.values().stream().map(value -> Map.of(
                "id", value.id(), "description", value.description())).toList();
    }

    public List<String> applications() {
        return applications.values().stream().sorted().toList();
    }

    public void openPath(Path path) {
        successful(runner.run(List.of("/usr/bin/open", path.toString()), timeout), "path could not be opened");
    }

    public void openUrl(String value) {
        URI uri = validatedUrl(value);
        successful(runner.run(List.of("/usr/bin/open", uri.toASCIIString()), timeout), "url could not be opened");
    }

    public void validateUrl(String value) {
        validatedUrl(value);
    }

    private URI validatedUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("url must be valid");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("only absolute http or https URLs can be opened");
        }
        return uri;
    }

    public void openApplication(String requested) {
        String application = applications.get(requested == null ? "" : requested.trim().toLowerCase(Locale.ROOT));
        if (application == null) throw new IllegalArgumentException("application is not allowlisted");
        successful(runner.run(List.of("/usr/bin/open", "-a", application), timeout),
                "application could not be opened");
    }

    public void runWorkflow(String id) {
        ComputerWorkflowDefinition workflow = workflows.get(id);
        if (workflow == null) throw new IllegalArgumentException("workflow is not allowlisted");
        successful(runner.run(workflow.command(), timeout), "workflow failed");
    }

    private void successful(com.minikun.guardian.GuardianCommandResult result, String message) {
        if (result.timedOut() || result.exitCode() != 0) throw new IllegalStateException(message);
    }
}
