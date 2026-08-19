package com.minikun.notification;

/** Application-facing notification boundary with durable delivery tracking. */
public interface NotificationDispatcher {
    void publish(NotificationRequest request);
}
