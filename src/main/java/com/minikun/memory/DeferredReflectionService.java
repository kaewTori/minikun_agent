package com.minikun.memory;

import com.minikun.memory.model.CompletedConversation;
import com.minikun.memory.reflection.ReflectionClient;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/** Bounded in-process deferral for home use; drops work when the queue is full. */
@Service
public final class DeferredReflectionService implements AutoCloseable {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "minikun-reflection");
        thread.setDaemon(true);
        return thread;
    });
    private final Semaphore capacity = new Semaphore(2);

    public boolean submit(ReflectionService service, CompletedConversation conversation) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(conversation, "conversation");
        if (!capacity.tryAcquire()) return false;
        executor.execute(() -> {
            try { service.reflect(conversation); }
            finally { capacity.release(); }
        });
        return true;
    }

    @Override public void close() {
        executor.shutdown();
        try { executor.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
