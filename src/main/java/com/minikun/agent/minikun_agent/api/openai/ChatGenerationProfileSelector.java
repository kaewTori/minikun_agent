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
    private final int technicalMaxTokens;

    public ChatGenerationProfileSelector(
            CooperationRouter cooperationRouter,
            @Value("${minikun.model.generation.profiles.enabled:true}") boolean enabled,
            @Value("${minikun.model.generation.profiles.companion-max-tokens:384}") int companionMaxTokens,
            @Value("${minikun.model.generation.profiles.focus-max-tokens:512}") int focusMaxTokens,
            @Value("${minikun.model.generation.profiles.general-max-tokens:768}") int generalMaxTokens,
            @Value("${minikun.model.generation.profiles.work-max-tokens:1536}") int workMaxTokens,
            @Value("${minikun.model.generation.profiles.technical-max-tokens:2048}") int technicalMaxTokens) {
        this.cooperationRouter = cooperationRouter;
        this.enabled = enabled;
        this.companionMaxTokens = positive(companionMaxTokens, "companion max tokens");
        this.focusMaxTokens = positive(focusMaxTokens, "focus max tokens");
        this.generalMaxTokens = positive(generalMaxTokens, "general max tokens");
        this.workMaxTokens = positive(workMaxTokens, "work max tokens");
        this.technicalMaxTokens = positive(technicalMaxTokens, "technical max tokens");
    }

    public Selection select(
            String userText, CompanionMode mode, boolean toolOrVisionRequest, int configuredMaximum) {
        int applicationMaximum = positive(configuredMaximum, "configured generation max tokens");
        if (!enabled) {
            return new Selection("default", applicationMaximum);
        }
        CooperationRoutingDecision route = cooperationRouter.decide(userText);
        if (route.needsExpert()) {
            return selection("technical", technicalMaxTokens, applicationMaximum);
        }
        if ("creative_request".equals(route.reason())) {
            return new Selection("creative", applicationMaximum);
        }
        if (toolOrVisionRequest || mode == CompanionMode.WORK) {
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
}
