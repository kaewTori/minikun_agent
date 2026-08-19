package com.minikun.computer;

import java.time.Instant;
import java.util.UUID;

public record ComputerAudit(
        UUID id, String ownerId, String conversationId, String operation,
        String target, String status, Instant createdAt, String detail) {}
