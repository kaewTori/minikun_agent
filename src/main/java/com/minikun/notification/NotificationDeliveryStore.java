package com.minikun.notification;

import java.util.List;

public interface NotificationDeliveryStore {
    void save(NotificationDelivery delivery);

    List<NotificationDelivery> list(
            String sourceType,
            String sourceId,
            NotificationDeliveryStatus status,
            int limit);
}
