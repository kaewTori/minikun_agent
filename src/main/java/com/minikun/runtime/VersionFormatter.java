package com.minikun.runtime;

import org.springframework.stereotype.Component;

@Component
public final class VersionFormatter {
    public String format(VersionInfo info) {
        return "Version\n"
                + "Application: " + RuntimeFormatter.value(info.applicationVersion()) + '\n'
                + "Build: " + RuntimeFormatter.value(info.buildVersion()) + '\n'
                + "Revision: " + RuntimeFormatter.value(info.revision()) + '\n'
                + "Java: " + RuntimeFormatter.value(info.javaVersion()) + '\n'
                + "Spring Boot: " + RuntimeFormatter.value(info.springBootVersion()) + '\n';
    }
}
