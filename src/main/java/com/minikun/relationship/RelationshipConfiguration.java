package com.minikun.relationship;

import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationSchedulerMonitor;
import com.minikun.proactive.ProactiveNotificationPolicy;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class RelationshipConfiguration {
    @Bean
    ConversationThreadStore conversationThreadStore(ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate template = jdbc.getIfAvailable();
        return template == null ? new InMemoryConversationThreadStore() : new JdbcConversationThreadStore(template);
    }

    @Bean
    ConversationThreadService conversationThreadService(ConversationThreadStore store, Clock clock,
            @Value("${minikun.relationship.timezone:Asia/Bangkok}") String zone) {
        return new ConversationThreadService(store, clock, ZoneId.of(zone));
    }

    @Bean
    ConversationThreadController conversationThreadController(ConversationThreadService service,
            @Value("${minikun.relationship.management.token:${minikun.memory.management.token:}}") String token) {
        return new ConversationThreadController(service, token);
    }

    @Bean
    ConversationCheckInScheduler conversationCheckInScheduler(ConversationThreadService service,
            NotificationDispatcher notifications, NotificationSchedulerMonitor monitor,
            ProactiveNotificationPolicy policy, Clock clock) {
        return new ConversationCheckInScheduler(service, notifications, monitor, policy, clock);
    }
}
