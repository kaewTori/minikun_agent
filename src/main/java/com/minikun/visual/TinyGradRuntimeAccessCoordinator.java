package com.minikun.visual;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Prevents TinyGrad health probes from entering its GPU runtime during generation. */
public final class TinyGradRuntimeAccessCoordinator {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final AtomicInteger pendingGenerations = new AtomicInteger();

    public <T> T generate(Supplier<T> operation) {
        Objects.requireNonNull(operation, "generation operation must not be null");
        pendingGenerations.incrementAndGet();
        lock.writeLock().lock();
        try {
            return operation.get();
        } finally {
            lock.writeLock().unlock();
            pendingGenerations.decrementAndGet();
        }
    }

    public HealthLease tryAcquireHealth() {
        if (pendingGenerations.get() > 0 || !lock.readLock().tryLock()) {
            return null;
        }
        if (pendingGenerations.get() > 0) {
            lock.readLock().unlock();
            return null;
        }
        return lock.readLock()::unlock;
    }

    @FunctionalInterface
    public interface HealthLease extends AutoCloseable {
        @Override
        void close();
    }
}
