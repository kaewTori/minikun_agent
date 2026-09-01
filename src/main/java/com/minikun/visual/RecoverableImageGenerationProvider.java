package com.minikun.visual;

import java.time.Duration;

/** Lets story fallback wait for a supervised local provider to finish restarting. */
public interface RecoverableImageGenerationProvider {
    boolean awaitAvailable(Duration timeout);
}
