package com.minikun.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DeferredReflectionServiceTest {
    @Test
    void defersTheWholeReflectionTask() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);

        try (DeferredReflectionService service = new DeferredReflectionService()) {
            assertTrue(service.submit(completed::countDown));
            assertTrue(completed.await(1, TimeUnit.SECONDS));
        }
    }
}
