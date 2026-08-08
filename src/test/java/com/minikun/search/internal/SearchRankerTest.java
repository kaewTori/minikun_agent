package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.search.model.SearchMetadata;
import com.minikun.search.model.SearchOptions;
import com.minikun.search.model.SearchResponse;
import com.minikun.search.model.SearchResult;
import com.minikun.search.model.SearchSource;
import com.minikun.search.model.SearchStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchRankerTest {
    @Test
    void ranksTitleAndTermMatchesBeforeWeakResults() {
        Instant now = Instant.now();
        SearchResult weak = result("Unrelated", "https://one.test/a", "other text", now, 1);
        SearchResult strong = result("Java 25 records", "https://two.test/a", "Java 25 record patterns", now, 2);
        SearchResponse response = new SearchResponse(UUID.randomUUID(), SearchStatus.SUCCESS,
                List.of(weak, strong), new SearchMetadata(Duration.ZERO, false, false, 0));

        SearchResponse ranked = new SearchRanker().rank(response, "Java 25 records", SearchOptions.defaults());

        assertEquals("Java 25 records", ranked.results().get(0).title());
    }

    private SearchResult result(String title, String url, String content, Instant retrievedAt, int position) {
        return new SearchResult(title, url, content, new SearchSource("test", url, retrievedAt), position);
    }
}
