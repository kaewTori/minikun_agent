package com.minikun.goal;

public enum GoalStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    ARCHIVED;

    public boolean open() {
        return this == ACTIVE || this == PAUSED;
    }
}
