package com.minikun.search.internal;

import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.model.SearchQuery;
import java.util.Objects;

public final class DefaultSearchQueryRewriteService implements SearchQueryRewriteService {
    @Override
    public SearchQuery rewrite(String query) {
        Objects.requireNonNull(query, "query must not be null");
        StringBuilder normalized = new StringBuilder(query.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < query.length();) {
            int codePoint = query.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                if (normalized.length() > 0) {
                    pendingSpace = true;
                }
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return new SearchQuery(new String(query), normalized.toString());
    }
}