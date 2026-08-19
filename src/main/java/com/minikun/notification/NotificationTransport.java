package com.minikun.notification;

/** Low-level transport boundary for sending a notification to an external channel. */
public interface NotificationTransport {
    void publish(NotificationChannel channel, String title, String message, int priority, String tags);
}
