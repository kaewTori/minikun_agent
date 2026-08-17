package com.minikun.pcs.model;

public record CapabilityInstruction(String name, String content, boolean required) {
    public CapabilityInstruction(String name, String content) {
        this(name, content, false);
    }
}
