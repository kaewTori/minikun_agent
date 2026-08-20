package com.minikun.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.ActiveChatModelProvider;
import com.minikun.personality.profile.UserModelService;
import com.minikun.tools.CommunicationAssistantTool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import({CommunicationController.class, CommunicationExceptionHandler.class, CommunicationAssistantTool.class})
public class CommunicationConfiguration {
    @Bean
    CommunicationService communicationService(
            ActiveChatModelProvider activeModel,
            ObjectMapper objectMapper,
            ObjectProvider<UserModelService> userModels,
            @Value("${minikun.communication.enabled:true}") boolean enabled,
            @Value("${minikun.communication.max-input-characters:12000}") int maxInputCharacters,
            @Value("${minikun.communication.max-output-characters:16000}") int maxOutputCharacters,
            @Value("${minikun.communication.max-output-tokens:1600}") int maxOutputTokens,
            @Value("${spring.ai.ollama.chat.options.model:}") String ollamaModel,
            @Value("${spring.ai.ollama.chat.options.num-ctx:16384}") int ollamaContextSize) {
        return new CommunicationService(activeModel, objectMapper, userModels.getIfAvailable(), enabled,
                maxInputCharacters, maxOutputCharacters, maxOutputTokens, ollamaModel, ollamaContextSize);
    }
}
