package com.minikun.search.internal;

import java.util.Locale;
import java.util.regex.Pattern;

/** Detects explicit image requests and visual-artist lookups without external calls. */
public final class ImageIntentDetector {
    private static final Pattern THAI_IMAGE_REQUEST = Pattern.compile(
            "(?:หารูป|ขอรูป|ขอภาพ|แสดงรูป|แสดงภาพ|อยาก(?:ดู|ได้|เห็น)(?:รูป|ภาพ)|ดูรูป|ดูภาพ|ต้องการ(?:รูป|ภาพ))(?!แบบ|รวม)");
    private static final Pattern THAI_IMAGE_OF_REQUEST = Pattern.compile("(?:รูป|ภาพ)(?!แบบ|รวม)ของ");
    private static final Pattern THAI_IMAGE_RECOMMENDATION = Pattern.compile(
            "(?:มี(?:รูป|ภาพ)(?!แบบ|รวม).*(?:แนะนำ|ไหม|มั้ย|หรือเปล่า)|"
                    + "(?:แนะนำ|ส่ง|คืน)(?:รูป|ภาพ)(?!แบบ|รวม)|"
                    + "(?:รูป|ภาพ)(?!แบบ|รวม).*แนะนำ)");
    private static final Pattern ENGLISH_IMAGE_REQUEST = Pattern.compile(
            "\\b(?:find|show|get|display|search for|look for|give me|want to see)\\b.*\\b(?:images?|pictures?|photos?)\\b");
    private static final Pattern ENGLISH_IMAGE_OF_REQUEST = Pattern.compile(
            "\\b(?:images?|pictures?|photos?)\\s+of\\b");
    private static final Pattern ENGLISH_IMAGE_RECOMMENDATION = Pattern.compile(
            "(?:\\bany\\b.*\\b(?:images?|pictures?|photos?)\\b|"
                    + "\\b(?:images?|pictures?|photos?)\\b.*\\brecommend)");
    private static final Pattern THAI_ARTWORK_FOLLOW_UP = Pattern.compile(
            "(?:มี.*(?:ผลงาน|งานวาด).*(?:แนะนำ|น่าสนใจ|ดู|ไหม|มั้ย)|"
                    + "(?:อยากดู|ขอดู|ช่วยหา|แนะนำ).*(?:ผลงาน|งานวาด)|"
                    + "(?:ผลงาน|งานวาด).*(?:แนะนำ|น่าสนใจ|อยากดู|ขอดู|ไหม|มั้ย))");
    private static final Pattern ENGLISH_ARTWORK_FOLLOW_UP = Pattern.compile(
            "\\b(?:recommend|show|find|see|interesting|any)\\b.*\\b(?:artworks?|works?|portfolio)\\b|"
                    + "\\b(?:artworks?|portfolio)\\b.*\\b(?:recommend|show|interesting|see)\\b");
    private static final Pattern VISUAL_ARTIST_CONTEXT = Pattern.compile(
            "(?:นักวาด|ศิลปิน(?:วาดภาพ)?|นักวาดภาพประกอบ|ภาพวาด|งานวาด|วาดรูป|อิลลัสเตรเตอร์|"
                    + "\\b(?:visual artist|illustrator|illustration|artwork|digital art|pixiv)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VISUAL_LOOK_REQUEST = Pattern.compile(
            "\\bwhat\\s+does\\s+.+\\s+look\\s+like\\b");
    private static final Pattern STRUCTURAL_IMAGE_TERM = Pattern.compile(
            "(?:รูปแบบ|ภาพรวม|\\bdesign\\s+pattern\\b|\\barchitecture\\s+(?:explanation|discussion)\\b)");

    public boolean detects(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (STRUCTURAL_IMAGE_TERM.matcher(normalized).find()) {
            return false;
        }
        return THAI_IMAGE_REQUEST.matcher(normalized).find()
                || THAI_IMAGE_OF_REQUEST.matcher(normalized).find()
                || THAI_IMAGE_RECOMMENDATION.matcher(normalized).find()
                || ENGLISH_IMAGE_REQUEST.matcher(normalized).find()
                || ENGLISH_IMAGE_OF_REQUEST.matcher(normalized).find()
                || ENGLISH_IMAGE_RECOMMENDATION.matcher(normalized).find()
                || VISUAL_LOOK_REQUEST.matcher(normalized).find();
    }

    public boolean detects(String query, String conversationContext) {
        if (detects(query)) {
            return true;
        }
        if (query == null || query.isBlank()
                || conversationContext == null || conversationContext.isBlank()
                || !VISUAL_ARTIST_CONTEXT.matcher(conversationContext).find()) {
            return false;
        }
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return THAI_ARTWORK_FOLLOW_UP.matcher(normalized).find()
                || ENGLISH_ARTWORK_FOLLOW_UP.matcher(normalized).find();
    }
}
