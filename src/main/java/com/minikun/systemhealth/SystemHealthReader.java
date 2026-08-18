package com.minikun.systemhealth;

/** Reads a sanitized snapshot of the host running Mini-kun. */
public interface SystemHealthReader {
    SystemHealthReport read();
}
