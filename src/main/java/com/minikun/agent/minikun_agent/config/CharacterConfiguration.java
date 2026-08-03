package com.minikun.agent.minikun_agent.config;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.McsSelector;
import com.minikun.pcs.PromptComposer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class CharacterConfiguration {
    @Bean
    PromptComposer promptComposer(McsSelector selector) {
        return new PromptComposer(selector);
    }

    @Bean
    McsSelector mcsSelector() {
        return new McsSelector();
    }

    @Bean
    CharacterSpecification characterSpecification(
            @Value("${mcs.root:${MCS_ROOT:../../config/minikun-agent/mcs}}") String characterRoot) {
        return new CharacterLoader(Path.of(characterRoot)).load();
    }
}