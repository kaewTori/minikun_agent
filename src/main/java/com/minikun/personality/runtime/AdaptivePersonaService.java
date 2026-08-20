package com.minikun.personality.runtime;

import com.minikun.personality.model.MoodSnapshot;
import com.minikun.personality.learning.AdaptivePreferenceLearningService;
import com.minikun.personality.profile.UserModelService;
import com.minikun.personality.signal.PersonaRuntimeInput;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/** Application facade for one user's adaptive persona state. */
@Service
@ConditionalOnBean(UserModelService.class)
public final class AdaptivePersonaService {
    private final AdaptivePersonaRuntime runtime;
    private final UserModelService userModelService;
    private final AdaptivePreferenceLearningService learningService;

    public AdaptivePersonaService(AdaptivePersonaRuntime runtime, UserModelService userModelService,
            AdaptivePreferenceLearningService learningService) {
        this.runtime = runtime;
        this.userModelService = userModelService;
        this.learningService = learningService;
    }

    public AdaptivePersonaResult evaluate(String ownerId, String message, boolean searchRequested,
            boolean toolRequested, boolean toolRequiresConfirmation, MoodSnapshot mood) {
        String owner = ownerId == null || ownerId.isBlank() ? "default" : ownerId;
        try {
            learningService.observe(owner, message);
        } catch (RuntimeException ignored) {
            // Adaptation is optional and must never make a chat request fail.
        }
        com.minikun.personality.model.PersonalUserModel userModel;
        try {
            userModel = userModelService.snapshot(owner);
        } catch (RuntimeException ignored) {
            userModel = com.minikun.personality.model.PersonalUserModel.EMPTY;
        }
        return runtime.evaluate(new PersonaRuntimeInput(owner, message, userModel.memories(), userModel.profile(),
                userModel.preferences(),
                toolRequested, toolRequiresConfirmation, searchRequested, mood));
    }
}
