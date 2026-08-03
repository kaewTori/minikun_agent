package com.minikun.runtime;

public record VersionInfo(
        RuntimeValue applicationVersion,
        RuntimeValue buildVersion,
        RuntimeValue revision,
        RuntimeValue javaVersion,
        RuntimeValue springBootVersion) {
}
