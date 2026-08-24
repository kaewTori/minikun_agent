package com.minikun.model;

/** Provider failure carrying whether another provider may safely retry the request. */
public final class ModelProviderException extends IllegalStateException {
    private final boolean retryable;

    public ModelProviderException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public ModelProviderException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
