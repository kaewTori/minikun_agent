package com.minikun.personality.feedback;

import com.minikun.personality.learning.AdaptivePreferenceLearningService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class ChatFeedbackConfiguration {
    @Bean ChatFeedbackStore chatFeedbackStore(ObjectProvider<JdbcTemplate> jdbc) {
        JdbcTemplate template = jdbc.getIfAvailable();
        return template == null ? new InMemoryChatFeedbackStore() : new JdbcChatFeedbackStore(template);
    }
    @Bean ChatFeedbackService chatFeedbackService(ChatFeedbackStore store,
            AdaptivePreferenceLearningService learning, Clock clock) {
        return new ChatFeedbackService(store, learning, clock);
    }
    @Bean ChatFeedbackController chatFeedbackController(ChatFeedbackService service,
            @Value("${minikun.adaptation.management.token:${minikun.memory.management.token:}}") String token) {
        return new ChatFeedbackController(service, token);
    }
}
