package com.minikun.search.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Small provider-neutral tokenizer with Thai phrase boundaries and search stopwords. */
final class SearchTermTokenizer {
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final List<String> THAI_PHRASES = List.of(
            "ร้านกาแฟ", "เปิดวันนี้", "สัปดาห์นี้", "เชียงใหม่", "กรุงเทพ", "ล่าสุด", "ข่าว",
            "เปรียบเทียบ", "ติดตั้ง", "วิธี", "ราคา", "รีวิว", "สเปก", "รุ่น", "เวอร์ชัน",
            "ปัจจุบัน", "เอกสาร", "คู่มือ", "ปัญหา", "แก้ไข", "บน", "สำหรับ",
            "แถว", "อยู่ที่ไหน");
    private static final Set<String> STOPWORDS = Set.of(
            "please", "could", "you", "tell", "me", "about", "what", "is", "the", "a", "an", "for",
            "หน่อย", "ให้หน่อย", "ครับ", "ค่ะ", "นะ", "ที", "ช่วย", "ค้นหา", "ค้น", "หา", "ขอ", "บอก",
            "ข้อมูล", "ของ", "ที่", "ให้");

    private SearchTermTokenizer() {
    }

    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String tokenized = query;
        for (String phrase : THAI_PHRASES.stream()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            tokenized = tokenized.replace(phrase, " " + phrase + " ");
        }
        List<String> terms = new ArrayList<>();
        for (String token : SPACE.split(tokenized)) {
            String clean = token.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "").trim();
            if (!clean.isBlank() && !isStopword(clean)) {
                terms.add(clean);
            }
        }
        return List.copyOf(terms.stream().distinct().toList());
    }

    private static boolean isStopword(String term) {
        return STOPWORDS.contains(term.toLowerCase(Locale.ROOT));
    }
}
