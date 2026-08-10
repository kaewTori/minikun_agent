package com.minikun.search.internal;

import java.util.Locale;
import java.util.regex.Pattern;

/** Detects explicit requests to find or view images without external calls. */
public final class ImageIntentDetector {
    private static final Pattern THAI_IMAGE_REQUEST = Pattern.compile(
            "(?:หารูป|ขอรูป|ขอภาพ|แสดงรูป|แสดงภาพ|อยากดูรูป|อยากดูภาพ|ดูรูป|ดูภาพ)(?!แบบ|รวม)");
    private static final Pattern THAI_IMAGE_OF_REQUEST = Pattern.compile("(?:รูป|ภาพ)(?!แบบ|รวม)ของ");
    private static final Pattern ENGLISH_IMAGE_REQUEST = Pattern.compile(
            "\\b(?:find|show|get|display|search for|look for|give me|want to see)\\b.*\\b(?:images?|pictures?|photos?)\\b");
    private static final Pattern ENGLISH_IMAGE_OF_REQUEST = Pattern.compile(
            "\\b(?:images?|pictures?|photos?)\\s+of\\b");
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
                || ENGLISH_IMAGE_REQUEST.matcher(normalized).find()
                || ENGLISH_IMAGE_OF_REQUEST.matcher(normalized).find()
                || VISUAL_LOOK_REQUEST.matcher(normalized).find();
    }
}