package com.minikun.computer;

import java.time.Instant;

public record ComputerEntry(String path, String type, long sizeBytes, Instant modifiedAt) {}
