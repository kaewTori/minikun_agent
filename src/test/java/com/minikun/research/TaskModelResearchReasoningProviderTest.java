package com.minikun.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelRequest;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskModelResearchReasoningProviderTest {
    @Test
    void parsesStructuredPlanAndCoverageEvaluation() {
        ArrayDeque<String> responses = new ArrayDeque<>(List.of(
                """
                {"objective":"Assess battery safety","questions":[
                  {"id":"q1","query":"official battery safety standard","purpose":"primary requirements"},
                  {"id":"q2","query":"independent battery incident data","purpose":"counter-check"}
                ]}
                """,
                """
                {"sufficient":false,"unresolvedGaps":["long-term data missing"],
                 "followUpQueries":["battery long-term incident trend"]}
                """));
        List<TaskModelRequest> requests = new java.util.ArrayList<>();
        TaskModelResearchReasoningProvider provider = new TaskModelResearchReasoningProvider(request -> {
            requests.add(request);
            return responses.removeFirst();
        }, new ObjectMapper());

        ResearchPlan plan = provider.plan("battery safety", "prior context", 4);
        ResearchEvaluation evaluation = provider.evaluate(
                plan, List.of("official battery safety standard"), "untrusted evidence", 2);

        assertEquals(2, plan.questions().size());
        assertEquals("independent battery incident data", plan.questions().get(1).query());
        assertFalse(evaluation.sufficient());
        assertEquals(List.of("long-term data missing"), evaluation.unresolvedGaps());
        assertEquals(List.of("battery long-term incident trend"), evaluation.followUpQueries());
        assertTrue(requests.stream().allMatch(request ->
                request.responseFormat() == TaskModelRequest.ResponseFormat.JSON_OBJECT));
        assertTrue(requests.get(1).messages().getLast().content().contains("<evidence>"));
    }
}
