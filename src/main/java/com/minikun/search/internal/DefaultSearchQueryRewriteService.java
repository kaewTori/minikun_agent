package com.minikun.search.internal;

import com.minikun.search.SearchQueryRewriteService;
import com.minikun.search.model.SearchQuery;
import java.util.Objects;

public final class DefaultSearchQueryRewriteService implements SearchQueryRewriteService {
    @Override
    public SearchQuery rewrite(String query) {
        Objects.requireNonNull(query, "query must not be null");
        String whitespaceNormalized = normalizeWhitespace(query);
        String structurallyNormalized = normalizeStructure(whitespaceNormalized);
        return new SearchQuery(new String(query), structurallyNormalized);
    }

    private String normalizeWhitespace(String query) {
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
        return normalized.toString();
    }

    private String normalizeStructure(String query) {
        StringBuilder normalized = new StringBuilder(query.length());
        for (int offset = 0; offset < query.length();) {
            int codePoint = query.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isZeroWidthCharacter(codePoint)) {
                continue;
            }
            if (codePoint == ' ') {
                if (normalized.length() == 0
                        || normalized.charAt(normalized.length() - 1) == ' ') {
                    continue;
                }
            }
            if (codePoint >= 0xFF01 && codePoint <= 0xFF5E) {
                codePoint -= 0xFEE0;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private boolean isZeroWidthCharacter(int codePoint) {
        return codePoint == 0x200B
                || codePoint == 0x200C
                || codePoint == 0x200D
                || codePoint == 0x2060
                || codePoint == 0xFEFF;
    }
}