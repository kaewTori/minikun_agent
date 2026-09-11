package com.minikun.sync;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class SyncEventBroker {
    private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final AtomicLong revision = new AtomicLong();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SseEmitter connect(String ownerId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        List<SseEmitter> ownerSubscribers = subscribers.computeIfAbsent(
                ownerId, ignored -> new CopyOnWriteArrayList<>());
        ownerSubscribers.add(emitter);
        Runnable remove = () -> {
            ownerSubscribers.remove(emitter);
            if (ownerSubscribers.isEmpty()) subscribers.remove(ownerId, ownerSubscribers);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(new SyncEvent(
                    revision.get(), "ready", null, null)));
        } catch (IOException exception) {
            remove.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public boolean publish(String ownerId, String type, UUID sourceDeviceId) {
        return publish(ownerId, type, sourceDeviceId, null);
    }

    public boolean publish(String ownerId, String type, UUID sourceDeviceId, Object payload) {
        SyncEvent event = new SyncEvent(revision.incrementAndGet(), type, sourceDeviceId, payload);
        List<SseEmitter> ownerSubscribers = subscribers.get(ownerId);
        if (ownerSubscribers == null || ownerSubscribers.isEmpty()) return false;
        boolean delivered = false;
        String eventName = "notification".equals(type) ? "notification" : "sync";
        for (SseEmitter emitter : ownerSubscribers) {
            try {
                emitter.send(SseEmitter.event().name(eventName).id(Long.toString(event.revision())).data(event));
                delivered = true;
            } catch (Exception exception) {
                ownerSubscribers.remove(emitter);
                emitter.complete();
            }
        }
        if (ownerSubscribers.isEmpty()) subscribers.remove(ownerId, ownerSubscribers);
        return delivered;
    }

    public boolean publishNotification(String ownerId, Map<String, Object> notification) {
        return publish(ownerId, "notification", null, notification);
    }

    public record SyncEvent(long revision, String type, UUID sourceDeviceId, Object payload) { }
}
