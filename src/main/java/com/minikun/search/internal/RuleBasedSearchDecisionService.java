package com.minikun.search.internal;

import com.minikun.search.SearchDecisionService;
import com.minikun.search.model.SearchDecision;
import java.util.List;

public final class RuleBasedSearchDecisionService implements SearchDecisionService {
    private static final List<String> KEYWORDS = List.of(
            "search", "ค้นหา", "แนะนำ", "ร้าน", "เมนู", "อาหาร", "ราคา", "ที่ไหน", "อยู่ที่ไหน",
            "ข่าว", "ล่าสุด", "วันนี้", "ปัจจุบัน", "ข้อมูล", "current", "latest", "news", "recommend",
            "where", "who is", "what is");

    @Override
    public SearchDecision decide(String query) {
        if (query == null || query.isBlank()) {
            return new SearchDecision(false, "");
        }
        String normalized = query.trim().toLowerCase();
        boolean shouldSearch = KEYWORDS.stream().anyMatch(normalized::contains);
        return new SearchDecision(shouldSearch, query.trim());
    }
}