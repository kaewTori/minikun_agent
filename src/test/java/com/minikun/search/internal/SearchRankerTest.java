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

    @Test
    void usesProviderScoreWhenLexicalScoresAreSimilar() {
        Instant now = Instant.now();
        SearchResult weakerProvider = new SearchResult(
                "Java records", "https://one.test/a", "Java records",
                new SearchSource("searxng", "https://one.test/a", now), 1, 0.1);
        SearchResult strongerProvider = new SearchResult(
                "Java records", "https://two.test/a", "Java records",
                new SearchSource("tavily", "https://two.test/a", now), 2, 0.95);
        SearchResponse response = new SearchResponse(UUID.randomUUID(), SearchStatus.SUCCESS,
                List.of(weakerProvider, strongerProvider), new SearchMetadata(Duration.ZERO, false, false, 0));

        SearchResponse ranked = new SearchRanker().rank(response, "Java records", SearchOptions.defaults());

        assertEquals("tavily", ranked.results().get(0).source().name());
    }

    @Test
    void usesSemanticAlternateQueriesToRankEvidenceRichLocalResults() {
        Instant now = Instant.now();
        SearchResult generic = result("ร้านข้าวไฟฉาย", "https://one.test/a", "ร้านอาหารทั่วไป", now, 1);
        SearchResult useful = result("ร้านข้าวไฟฉาย", "https://two.test/a",
                "รีวิว 4.8 ดาว เปิดถึง 20:00 ราคา 100 บาท", now, 2);
        SearchResponse response = new SearchResponse(UUID.randomUUID(), SearchStatus.SUCCESS,
                List.of(generic, useful), new SearchMetadata(Duration.ZERO, false, false, 0));

        SearchResponse ranked = new SearchRanker().rank(response,
                List.of("ร้านข้าวไฟฉาย", "ร้านข้าวไฟฉาย รีวิว เวลาเปิด ราคา"), SearchOptions.defaults());

        assertEquals("https://two.test/a", ranked.results().get(0).canonicalUri());
    }

    private SearchResult result(String title, String url, String content, Instant retrievedAt, int position) {
        return new SearchResult(title, url, content, new SearchSource("test", url, retrievedAt), position);
    }
}
