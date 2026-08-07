package com.minikun.agent.minikun_agent.config;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.McsSelector;
import com.minikun.pcs.InterestsSelectionStrategy;
import com.minikun.pcs.CatchphrasesSelectionStrategy;
import com.minikun.pcs.CompositeInterestSelectionSignalProducer;
import com.minikun.pcs.LiteralTermMatcher;
import com.minikun.pcs.ModuleSelectionStrategyRegistry;
import com.minikun.pcs.SelectAllStrategy;
import com.minikun.pcs.SectionKind;
import com.minikun.pcs.PromptComposer;
import com.minikun.pcs.InterestSelectionSignalProducer;
import com.minikun.pcs.NoOpInterestSelectionSignalProducer;
import com.minikun.pcs.SearchInterestSelectionSignalProducer;
import com.minikun.pcs.SelectionContextFactory;
import com.minikun.search.SearchSelectionSignalMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Configuration
public class CharacterConfiguration {
    @Bean
    PromptComposer promptComposer(McsSelector selector, SelectionContextFactory contextFactory) {
        return new PromptComposer(selector, contextFactory);
    }

    @Bean
    NoOpInterestSelectionSignalProducer noOpInterestSelectionSignalProducer() {
        return new NoOpInterestSelectionSignalProducer();
    }

    @Bean
    SearchSelectionSignalMapper searchSelectionSignalMapper() {
        return new SearchSelectionSignalMapper();
    }

    @Bean
    SearchInterestSelectionSignalProducer searchInterestSelectionSignalProducer() {
        return new SearchInterestSelectionSignalProducer();
    }

    @Bean
    @Primary
    CompositeInterestSelectionSignalProducer compositeInterestSelectionSignalProducer(
            SearchInterestSelectionSignalProducer searchProducer) {
        return new CompositeInterestSelectionSignalProducer(List.of(searchProducer));
    }

    @Bean
    SelectionContextFactory selectionContextFactory(
            InterestSelectionSignalProducer signalProducer) {
        return new SelectionContextFactory(signalProducer);
    }

    @Bean
    McsSelector mcsSelector(ModuleSelectionStrategyRegistry strategyRegistry) {
        return new McsSelector(strategyRegistry);
    }

    @Bean
    SelectAllStrategy selectAllStrategy() {
        return new SelectAllStrategy();
    }

    @Bean
    InterestsSelectionStrategy interestsSelectionStrategy() {
        return new InterestsSelectionStrategy(literalTermMatcher());
    }

    @Bean
    CatchphrasesSelectionStrategy catchphrasesSelectionStrategy() {
        return new CatchphrasesSelectionStrategy(literalTermMatcher());
    }

    @Bean
    LiteralTermMatcher literalTermMatcher() {
        return new LiteralTermMatcher();
    }

    @Bean
    ModuleSelectionStrategyRegistry moduleSelectionStrategyRegistry(
            SelectAllStrategy fallback,
            InterestsSelectionStrategy interests,
            CatchphrasesSelectionStrategy catchphrases) {
        return ModuleSelectionStrategyRegistry.of(
                Map.of(
                        SectionKind.INTERESTS, interests,
                        SectionKind.CATCHPHRASES, catchphrases), fallback);
    }

    @Bean
    CharacterSpecification characterSpecification(
            @Value("${mcs.root:${MCS_ROOT:../../config/minikun-agent/mcs}}") String characterRoot) {
        return new CharacterLoader(Path.of(characterRoot)).load();
    }
}