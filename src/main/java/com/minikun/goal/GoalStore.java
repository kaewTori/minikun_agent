package com.minikun.goal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalStore {
    PersonalGoal create(PersonalGoal goal);
    Optional<PersonalGoal> find(UUID id, String ownerId);
    List<PersonalGoal> list(String ownerId, GoalStatus status);
    default List<PersonalGoal> dueForReview(String ownerId, Instant now, int limit) {
        return list(ownerId, GoalStatus.ACTIVE).stream()
                .filter(goal -> goal.nextReviewAt() != null && !goal.nextReviewAt().isAfter(now))
                .limit(Math.max(1, limit))
                .toList();
    }
    PersonalGoal update(PersonalGoal goal);
}
