package com.minikun.research;

import java.util.Locale;
import java.util.regex.Pattern;

/** Detects explicit research depth and useful narrative structure without a model round trip. */
public final class ResearchIntentDetector {
    private static final Pattern DEEP_RESEARCH = Pattern.compile(
            "(ค้นคว้า|วิจัย|เจาะลึก|สืบค้น|ตรวจสอบข้อเท็จจริง|หลายแหล่ง|แหล่งข้อมูลปฐมภูมิ|"
                    + "deep\\s+research|research|investigat(?:e|ion)|fact[- ]?check|deep\\s+dive|"
                    + "multiple\\s+sources|primary\\s+sources)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern STORY = Pattern.compile(
            "(เล่า(?:ให้ฟัง|เป็นเรื่อง|เรื่อง)?|เรื่องราว|สตอรี่|เขียนฉาก|สร้างฉาก|บรรยายฉาก|"
                    + "ฉากเปิด|ฉากจบ|ต่อเรื่อง|ตอนต่อไป|narrative|tell\\s+(?:me\\s+)?(?:a\\s+)?story|storytelling|"
                    + "(?:write|describe|create)\\s+(?:a|the\\s+)?scene|"
                    + "continue (?:the )?story|next chapter)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIMELINE = Pattern.compile(
            "(ประวัติ|ที่มา|พัฒนาการ|ลำดับเหตุการณ์|ไทม์ไลน์|timeline|history|evolution|chronolog)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMPARISON = Pattern.compile(
            "(เปรียบเทียบ|เทียบกับ|ข้อดีข้อเสีย|ต่างกัน|comparison|compare|versus|\\bvs\\.?\\b|pros and cons)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ANALYSIS = Pattern.compile(
            "(วิเคราะห์|เจาะลึก|ผลกระทบ|นัยสำคัญ|มุมมอง|analysis|analy[sz]e|deep\\s+dive|implications?)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern EXPLANATION = Pattern.compile(
            "(อธิบาย|ทำไม|อย่างไร|ทำงานยังไง|คืออะไร|explain|how does|how do|why does|why is|what is)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public ResearchIntent detect(String message) {
        String value = message == null ? "" : message.strip().toLowerCase(Locale.ROOT);
        boolean research = DEEP_RESEARCH.matcher(value).find();
        boolean story = STORY.matcher(value).find();
        NarrativeMode mode = narrativeMode(value, story);
        return new ResearchIntent(research, story || mode != NarrativeMode.DIRECT, mode);
    }

    private NarrativeMode narrativeMode(String value, boolean story) {
        if (story) {
            return NarrativeMode.STORY;
        }
        if (TIMELINE.matcher(value).find()) {
            return NarrativeMode.TIMELINE;
        }
        if (COMPARISON.matcher(value).find()) {
            return NarrativeMode.COMPARISON;
        }
        if (ANALYSIS.matcher(value).find()) {
            return NarrativeMode.ANALYSIS;
        }
        if (EXPLANATION.matcher(value).find()) {
            return NarrativeMode.EXPLANATION;
        }
        return NarrativeMode.DIRECT;
    }
}
