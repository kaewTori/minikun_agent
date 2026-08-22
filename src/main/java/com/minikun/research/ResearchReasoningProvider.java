package com.minikun.research;

import java.util.List;

public interface ResearchReasoningProvider {
    ResearchPlan plan(String userQuery, String conversationContext, int maximumQuestions);

    ResearchEvaluation evaluate(
            ResearchPlan plan,
            List<String> executedQueries,
            String evidenceDigest,
            int maximumFollowUpQueries);
}
