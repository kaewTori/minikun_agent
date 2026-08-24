package com.minikun.systemhealth;

import java.util.Map;

/** Reads sanitized TinyGrad NV allocator counters for the Cockpit. */
@FunctionalInterface
interface NvAllocatorMemoryProbe {
    Map<String, Object> read();
}
