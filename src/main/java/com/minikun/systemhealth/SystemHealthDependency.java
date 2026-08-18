package com.minikun.systemhealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A local TCP endpoint that is safe for the system health probe to check. */
public record SystemHealthDependency(String name, String host, int port) {
    public SystemHealthDependency {
        Objects.requireNonNull(name, "dependency name must not be null");
        Objects.requireNonNull(host, "dependency host must not be null");
        if (name.isBlank() || host.isBlank()) {
            throw new IllegalArgumentException("dependency name and host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("dependency port must be between 1 and 65535");
        }
    }

    /**
     * Parses the application-owned allowlist format:
     * {@code name=host:port,name=host:port}.
     */
    public static List<SystemHealthDependency> parseList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<SystemHealthDependency> dependencies = new ArrayList<>();
        for (String item : value.split(",")) {
            dependencies.add(parse(item));
        }
        return List.copyOf(dependencies);
    }

    private static SystemHealthDependency parse(String item) {
        String[] nameAndAddress = item.trim().split("=", 2);
        if (nameAndAddress.length != 2) {
            throw new IllegalArgumentException("system health dependency must use name=host:port");
        }

        String address = nameAndAddress[1].trim();
        int separator = address.lastIndexOf(':');
        if (separator < 1 || separator == address.length() - 1) {
            throw new IllegalArgumentException("system health dependency must use name=host:port");
        }

        String host = address.substring(0, separator).trim();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        try {
            return new SystemHealthDependency(
                    nameAndAddress[0].trim(), host, Integer.parseInt(address.substring(separator + 1).trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("system health dependency port must be a number", exception);
        }
    }
}
