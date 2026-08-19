package com.minikun.task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskStore {
    PersonalTask create(PersonalTask task);

    Optional<PersonalTask> find(UUID id, String ownerId);

    List<PersonalTask> list(String ownerId, TaskStatus status);

    List<PersonalTask> findDueFollowUps(Instant now);

    PersonalTask update(PersonalTask task);

    boolean delete(UUID id, String ownerId, Instant updatedAt);

    void markFollowedUp(PersonalTask task, Instant now);
}
