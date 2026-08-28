package com.minikun.search.internal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minikun.search.SearchProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SearchConfigurationTest {
    @Test
    void routesDirectlyToSearxngWhenTavilyIsNotConfigured() {
        TavilySearchProvider tavily = mock(TavilySearchProvider.class);
        SearchProvider searxng = mock(SearchProvider.class);
        when(tavily.configured()).thenReturn(false);

        SearchProvider selected = new SearchConfiguration().searchProvider(
                tavily, searxng, Clock.systemUTC(), new SimpleMeterRegistry(),
                true, Duration.ofMinutes(2), 3);

        assertSame(searxng, selected);
    }
}
