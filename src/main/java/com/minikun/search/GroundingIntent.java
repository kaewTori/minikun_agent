package com.minikun.search;

import java.util.regex.Pattern;

/** Small shared guard for requests that depend on exact external wording. */
public final class GroundingIntent {
    private static final Pattern VERBATIM = Pattern.compile(
            "(?iu)(เนื้อเพลง|เนื้อร้อง|ข้อความต้นฉบับ|คำพูดต้นฉบับ|คำพูดจริง|"
                    + "อ้างคำพูด|อ้างข้อความ|lyrics?\\b|verbatim\\b|exact (?:text|wording|quote)|"
                    + "original (?:text|wording|lyrics))");
    private static final Pattern SONG_ANALYSIS = Pattern.compile(
            "(?iu)(?:แปล|วิเคราะห์|อธิบาย).*(?:เพลง|\\bsong\\b)");
    private static final Pattern TRANSLATE = Pattern.compile("(?iu)(?:แปล|translate)");
    private static final Pattern SONG_CONTEXT = Pattern.compile("(?iu)(?:เพลง|\\bsong\\b|lyrics?\\b)");
    private static final Pattern SOURCE_FOLLOW_UP = Pattern.compile(
            "(?iu)(?:ทั้งเพลง|เพลงนั้น|เพลงนี้|ท่อนต่อ|ท่อนถัดไป|ทำแบบนี้|อีกที|ขอใหม่|ต่อเลย)");
    private static final Pattern CORRECTION = Pattern.compile(
            "(?iu)(?:ไม่ใช่|ไม่ตรง|ผิด(?:หมด|ทั้ง|ไป)?|มั่ว|แก้(?:ไข)?|จริง[ ๆ]*คือ|"
                    + "ตรวจ(?:สอบ)?ใหม่|wrong\\b|incorrect\\b|actually\\b|that's not right)");
    private static final Pattern PROVIDED_TEXT = Pattern.compile(
            "(?is)(?:แปล|วิเคราะห์|translate|analy[sz]e).{0,80}[:：]\\s*[^\\r\\n]{40,}");

    private GroundingIntent() { }

    public static boolean requiresSource(String query, String conversationContext) {
        String current = query == null ? "" : query;
        String context = conversationContext == null ? "" : conversationContext;
        return VERBATIM.matcher(current).find()
                || SONG_ANALYSIS.matcher(current).find()
                || TRANSLATE.matcher(current).find() && SONG_CONTEXT.matcher(context).find()
                || SOURCE_FOLLOW_UP.matcher(current).find() && VERBATIM.matcher(context).find();
    }

    public static boolean correction(String query) {
        return CORRECTION.matcher(query == null ? "" : query).find();
    }

    public static boolean transformsProvidedText(String query) {
        return PROVIDED_TEXT.matcher(query == null ? "" : query).find();
    }
}
