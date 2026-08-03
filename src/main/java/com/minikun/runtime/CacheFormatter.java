package com.minikun.runtime;

import org.springframework.stereotype.Component;

@Component
public final class CacheFormatter {
    public String format(CacheInfo info) {
        return "Cache\n"
                + "Enabled: " + RuntimeFormatter.value(info.enabled()) + '\n'
                + "Backend: " + RuntimeFormatter.value(info.backend()) + '\n'
                + "TTL: " + RuntimeFormatter.value(info.ttl()) + '\n';
    }
}
