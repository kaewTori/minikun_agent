package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.model.CooperationRouter;
import com.minikun.model.CooperationRoutingDecision;
import com.minikun.personality.companion.CompanionMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Selects a conservative output ceiling before the context budget applies its own cap. */
@Component
public final class ChatGenerationProfileSelector {
    private final CooperationRouter cooperationRouter;
    private final boolean enabled;
    private final int companionMaxTokens;
    private final int focusMaxTokens;
    private final int generalMaxTokens;
    private final int workMaxTokens;
    private final int searchMaxTokens;
    private final int researchMaxTokens;
    private final int technicalMaxTokens;
    private final int creativeMaxTokens;

    public ChatGenerationProfileSelector(
            CooperationRouter cooperationRouter,
            @Value("${minikun.model.generation.profiles.enabled:true}") boolean enabled,
            @Value("${minikun.model.generation.profiles.companion-max-tokens:384}") int companionMaxTokens,
            @Value("${minikun.model.generation.profiles.focus-max-tokens:512}") int focusMaxTokens,
            @Value("${minikun.model.generation.profiles.general-max-tokens:1536}") int generalMaxTokens,
            @Value("${minikun.model.generation.profiles.work-max-tokens:1536}") int workMaxTokens,
            @Value("${minikun.model.generation.profiles.search-max-tokens:3072}") int searchMaxTokens,
            @Value("${minikun.model.generation.profiles.research-max-tokens:4096}") int researchMaxTokens,
            @Value("${minikun.model.generation.profiles.technical-max-tokens:2048}") int technicalMaxTokens,
            @Value("${minikun.model.generation.profiles.creative-max-tokens:4096}") int creativeMaxTokens) {
        this.cooperationRouter = cooperationRouter;
        this.enabled = enabled;
        this.companionMaxTokens = positive(companionMaxTokens, "companion max tokens");
        this.focusMaxTokens = positive(focusMaxTokens, "focus max tokens");
        this.generalMaxTokens = positive(generalMaxTokens, "general max tokens");
        this.workMaxTokens = positive(workMaxTokens, "work max tokens");
        this.searchMaxTokens = positive(searchMaxTokens, "search max tokens");
        this.researchMaxTokens = positive(researchMaxTokens, "research max tokens");
        this.technicalMaxTokens = positive(technicalMaxTokens, "technical max tokens");
        this.creativeMaxTokens = positive(creativeMaxTokens, "creative max tokens");
    }

    public Selection select(
            String userText, CompanionMode mode, boolean toolOrVisionRequest, int configuredMaximum) {
        return select(userText, mode,
                new GenerationSignals(toolOrVisionRequest, false, false, false), configuredMaximum);
    }

    Selection select(
            String userText,
            CompanionMode mode,
            GenerationSignals signals,
            int configuredMaximum) {
        int applicationMaximum = positive(configuredMaximum, "configured generation max tokens");
        if (!enabled) {
            return new Selection("default", applicationMaximum);
        }
        CooperationRoutingDecision route = cooperationRouter.decide(userText);
        if (signals.creativeConversation() || "creative_request".equals(route.reason())) {
            return selection("creative", creativeMaxTokens, applicationMaximum);
        }
        if (signals.deepResearch()) {
            return selection("research", researchMaxTokens, applicationMaximum);
        }
        if (signals.searchRequested()) {
            return selection("search", searchMaxTokens, applicationMaximum);
        }
        if (route.needsExpert()) {
            return selection("technical", technicalMaxTokens, applicationMaximum);
        }
        if (signals.toolOrVisionRequest() || mode == CompanionMode.WORK) {
            return selection("work", workMaxTokens, applicationMaximum);
        }
        if (mode == CompanionMode.FOCUS) {
            return selection("focus", focusMaxTokens, applicationMaximum);
        }
        if (mode == CompanionMode.COMPANION) {
            return selection("companion", companionMaxTokens, applicationMaximum);
        }
        return selection("general", generalMaxTokens, applicationMaximum);
    }

    boolean isCreativeRequest(String userText) {
        return "creative_request".equals(cooperationRouter.decide(userText).reason());
    }

    private Selection selection(String profile, int profileMaximum, int applicationMaximum) {
        return new Selection(profile, Math.min(profileMaximum, applicationMaximum));
    }

    private int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    public record Selection(String profile, int maxTokens) {
    }

    record GenerationSignals(
            boolean toolOrVisionRequest,
            boolean searchRequested,
            boolean deepResearch,
            boolean creativeConversation) {
    }
}
