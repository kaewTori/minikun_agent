package com.minikun.communication;

import java.util.List;

public record CommunicationStatus(
        boolean enabled,
        boolean localModel,
        boolean storesContent,
        boolean sendSupported,
        List<String> actions,
        List<String> channels) {
}
