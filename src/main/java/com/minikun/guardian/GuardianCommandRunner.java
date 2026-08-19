package com.minikun.guardian;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface GuardianCommandRunner {
    GuardianCommandResult run(List<String> command, Duration timeout);
}
