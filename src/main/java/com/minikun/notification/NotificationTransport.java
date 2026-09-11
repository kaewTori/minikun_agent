package com.minikun.notification;

/** Low-level transport boundary for sending a notification to an external channel. */
public interface NotificationTransport {
    /** Returns whether the transport accepted the notification. */
    boolean publish(NotificationChannel channel, String title, String message, int priority, String tags);
}
