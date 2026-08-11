package com.minikun.model;

public record ModelCapabilities(
        boolean streaming,
        boolean toolCalling,
        boolean vision) {
}