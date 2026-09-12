package com.minikun.search.internal;

import java.util.ArrayList;
import java.util.List;

import com.minikun.pcs.KnowledgeCandidate;
import com.minikun.pcs.KnowledgeSource;
import com.minikun.pcs.model.ImageSource;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.search.ImageSearchProvider;
import com.minikun.search.model.ImageSearchRequest;
import com.minikun.search.model.SearchProviderResponse;

/** Normalizes reverse-image provider results into the existing knowledge/gallery contract. */
public final class DefaultImageSearchService {
    private final ImageSearchProvider provider;
    private final SearchFormatter formatter;

    public DefaultImageSearchService(ImageSearchProvider provider) {
        this(provider, new SearchFormatter());
    }

    public DefaultImageSearchService(ImageSearchProvider provider, SearchFormatter formatter) {
        this.provider = java.util.Objects.requireNonNull(provider, "image provider must not be null");
        this.formatter = java.util.Objects.requireNonNull(formatter, "search formatter must not be null");
    }

    public KnowledgeContext search(ImageSearchRequest request) {
        SearchProviderResponse response = provider.search(request);
        if (response == null) {
            return KnowledgeContext.empty();
        }
        KnowledgeContext imageContext = formatter.formatImagesByImage(response.images(), request.resultLimit());
        List<KnowledgeCandidate> evidence = new ArrayList<>();
        for (int index = 0; index < imageContext.images().size(); index++) {
            ImageSource image = imageContext.images().get(index);
            if (image.sourceUrl() == null || image.sourceUrl().isBlank()) {
                continue;
            }
            String title = image.title() == null || image.title().isBlank()
                    ? "Image search result" : image.title();
            String description = image.description() == null || image.description().isBlank()
                    ? "" : ": " + image.description();
            evidence.add(new KnowledgeCandidate(
                    "image-search-" + index,
                    KnowledgeSource.SEARCH,
                    title + " (" + image.sourceUrl() + ")" + description,
                    index,
                    image.sourceUrl()));
        }
        String content = evidence.stream()
                .map(KnowledgeCandidate::content)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return new KnowledgeContext(content, evidence, imageContext.images());
    }
}
