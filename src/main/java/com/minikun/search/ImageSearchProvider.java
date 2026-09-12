package com.minikun.search;

import com.minikun.search.model.ImageSearchRequest;
import com.minikun.search.model.SearchProviderResponse;

/** External boundary for reverse-image search. */
@FunctionalInterface
public interface ImageSearchProvider {
    SearchProviderResponse search(ImageSearchRequest request);
}
