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
        Runnable remove = () -> ownerSubscribers.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(error -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("ready").data(new SyncEvent(
                    revision.get(), "ready", null)));
        } catch (IOException exception) {
            remove.run();
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    public void publish(String ownerId, String type, UUID sourceDeviceId) {
        SyncEvent event = new SyncEvent(revision.incrementAndGet(), type, sourceDeviceId);
        List<SseEmitter> ownerSubscribers = subscribers.getOrDefault(ownerId, new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : ownerSubscribers) {
            try {
                emitter.send(SseEmitter.event().name("sync").id(Long.toString(event.revision())).data(event));
            } catch (Exception exception) {
                ownerSubscribers.remove(emitter);
                emitter.complete();
            }
        }
    }

    public record SyncEvent(long revision, String type, UUID sourceDeviceId) { }
}
