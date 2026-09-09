package com.minikun.personalloop;

import static com.minikun.personalloop.PersonalLoopModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class PersonalLoopStoreTest {
    @Test
    void keepsPersonalLoopUsableWhenDatabaseFailsAfterStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("database down"))
                .when(jdbc).update(anyString(), any(Object[].class));
        PersonalLoopStore store = new PersonalLoopStore(jdbc, new ObjectMapper());
        Instant start = Instant.parse("2026-09-09T00:00:00Z");
        WeeklyReview review = new WeeklyReview(UUID.randomUUID(), "owner-a", "offline-conversation",
                start, start.plusSeconds(60), ReviewStatus.DRAFT, Map.of(), start, null);

        store.save(review);

        assertEquals(review, store.review(review.id(), "owner-a").orElseThrow());
        assertEquals(1, store.reviews("owner-a", 10).size());
    }
}
