package com.minikun.agent.minikun_agent.api.openai;

import java.util.List;
import com.minikun.visual.StoryIllustrationService;

/** Small adapter for optional illustration work outside the chat facade. */
final class ChatIllustrationSupport {
    private ChatIllustrationSupport() { }

    static boolean requested(StoryIllustrationService service, TurnPlan plan) {
        return service != null && plan != null && plan.imageOutput();
    }

    static StoryIllustrationService.IllustrationResult illustrate(
            StoryIllustrationService service, String ownerId, String conversationId, String visualRequest,
            String assistantContent, TurnPlan turnPlan) {
        if (service == null) return new StoryIllustrationService.IllustrationResult(List.of(), "");
        return service.illustrate(ownerId, conversationId, visualRequest, assistantContent,
                turnPlan != null && turnPlan.imageOutput());
    }
}
