package com.minikun.goal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoalStore {
    PersonalGoal create(PersonalGoal goal);
    Optional<PersonalGoal> find(UUID id, String ownerId);
    List<PersonalGoal> list(String ownerId, GoalStatus status);
    PersonalGoal update(PersonalGoal goal);
}
