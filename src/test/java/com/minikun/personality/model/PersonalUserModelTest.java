package com.minikun.personality.model;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minikun.memory.model.Memory;
import com.minikun.memory.model.MemoryCategory;
import com.minikun.memory.model.MemoryId;
import com.minikun.memory.model.MemorySource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonalUserModelTest {
    @Test
    void rendersProfilePreferencesAndFactsAsBackgroundContext() {
        var model = new PersonalUserModel("owner-a",
                new UserProfile("owner-a", "พี่", "th", "concise", "Asia/Bangkok"),
                List.of(new Preference("owner-a", "language", "Thai", .9, Instant.now())),
                List.of(new Memory("owner-a", "conversation", MemoryId.generate(), MemoryCategory.GOAL,
                        MemorySource.USER_DIRECTIVE, "กำลังทำ homelab", Instant.now(), 1.0, "direct")),
                Instant.now());

        String prompt = model.promptContent();

        assertTrue(prompt.contains("display_name: พี่"));
        assertTrue(prompt.contains("language: Thai"));
        assertTrue(prompt.contains("[goal] กำลังทำ homelab"));
        assertTrue(prompt.contains("background context"));
    }
}
