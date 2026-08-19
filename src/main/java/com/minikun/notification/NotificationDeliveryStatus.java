package com.minikun.notification;

public enum NotificationDeliveryStatus {
    DELIVERED,
    FAILED;

    public static NotificationDeliveryStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("notification status must be DELIVERED or FAILED", exception);
        }
    }
}
