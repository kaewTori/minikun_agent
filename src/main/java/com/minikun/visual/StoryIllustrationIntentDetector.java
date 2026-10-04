package com.minikun.visual;

import com.minikun.model.CooperationRouter;
import com.minikun.research.ResearchIntentDetector;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministically detects image creation without confusing it with image search. */
public final class StoryIllustrationIntentDetector {
    public static final String PREVIOUS_GRAPHIC_CONTEXT = "\n\nบริบทงานภาพเดิม (ใช้เนื้อหา แต่ยึดรูปแบบตามคำขอปัจจุบัน):\n";

    private static final Pattern NO_IMAGE = Pattern.compile(
            "(?:ไม่ต้อง|อย่า|ห้าม|don't|do not).{0,8}"
                    + "(?:สร้าง|วาด|เจน|ทำ|ปรับ|เปลี่ยน|แก้|แปลง|generate|draw|create|make|revise|change|convert).{0,20}"
                    + "(?:ภาพ|รูป|แผนผัง|ผังงาน|อินโฟกราฟิก|infographic|flowchart|diagram|timeline|chart|mind ?map|images?|pictures?)|"
                    + "(?:ไม่เอา|ไม่ต้องการ)(?:ภาพ|รูป)ประกอบ|without (?:an? )?(?:image|picture)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VECTOR_GRAPHIC = Pattern.compile(
            "(?:ทำ|สร้าง|วาด|ออกแบบ|ขอ|อยากได้|ปรับ|เปลี่ยน|แก้|แปลง|ช่วยทำ|ช่วยสร้าง|create|draw|make|design|revise|change|convert|redraw)"
                    + ".{0,60}(?:อินโฟกราฟิก|infographic|(?:รูปแบบ|แบบ)\\s*info\\b|ผังงาน|แผนผัง|แผนภาพ|ไทม์ไลน์|กราฟ(?!ิก)|การ์ดข้อความ|"
                    + "flowchart|diagram|timeline|chart|mind ?map|text card)|"
                    + "(?:อินโฟกราฟิก|infographic|(?:รูปแบบ|แบบ)\\s*info\\b|ผังงาน|แผนผัง|แผนภาพ|ไทม์ไลน์|กราฟ(?!ิก)|การ์ดข้อความ|"
                    + "flowchart|diagram|timeline|chart|mind ?map|text card).{0,30}"
                    + "(?:ให้หน่อย|ให้ที|please|สร้าง|วาด|ทำ|design|create)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RASTER_FORMAT = Pattern.compile(
            "(?:\\b(?:png|jpe?g|webp)\\b|ภาพถ่าย|photorealistic)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern DIRECT_IMAGE = Pattern.compile(
            "(?:สร้าง|วาด|เจน|เจเนอเรต|ทำ|ออกแบบ|gen(?:erate)?|create|draw|make|design)"
                    + ".{0,40}(?:ภาพ|รูป|อิลลัส(?:เตรชัน)?|ภาพประกอบ|images?|pictures?|illustrations?|artwork)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VISUAL_COMPANION = Pattern.compile(
            "(?:พร้อม|มี|ใส่|แทรก|แนบ|ประกอบ|เพิ่ม).{0,30}(?:ภาพ|รูป|อิลลัส(?:เตรชัน)?|ภาพประกอบ)|"
                    + "(?:ภาพ|รูป|ภาพประกอบ).{0,30}(?:ประกอบ|แต่ละฉาก|เรื่อง|นิทาน)|"
                    + "(?:with|include|add|accompanied by).{0,30}(?:images?|pictures?|illustrations?|artwork)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final CooperationRouter cooperationRouter;
    private final ResearchIntentDetector narrativeDetector = new ResearchIntentDetector();

    public StoryIllustrationIntentDetector(CooperationRouter cooperationRouter) {
        this.cooperationRouter = cooperationRouter;
    }

    StoryIllustrationIntentDetector() {
        this(new CooperationRouter());
    }

    public static String currentRequest(String message) {
        if (message == null) return "";
        int contextStart = message.indexOf(PREVIOUS_GRAPHIC_CONTEXT);
        return contextStart < 0 ? message : message.substring(0, contextStart);
    }

    public StoryIllustrationIntent detect(String message, boolean autoIllustrateCreativeStories) {
        String value = currentRequest(message).strip().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return StoryIllustrationIntent.NONE;
        }
        if (NO_IMAGE.matcher(value).find()) {
            return StoryIllustrationIntent.NONE;
        }
        if (VECTOR_GRAPHIC.matcher(value).find() && !RASTER_FORMAT.matcher(value).find()) {
            return StoryIllustrationIntent.VECTOR_GRAPHIC;
        }
        if (DIRECT_IMAGE.matcher(value).find()) {
            return StoryIllustrationIntent.DIRECT_IMAGE;
        }
        if (VISUAL_COMPANION.matcher(value).find()
                && narrativeDetector.detect(value).storytellingRequested()) {
            return StoryIllustrationIntent.EXPLICIT_STORY_ILLUSTRATION;
        }
        if (autoIllustrateCreativeStories
                && "creative_request".equals(cooperationRouter.decide(value).reason())) {
            return StoryIllustrationIntent.AUTOMATIC_CREATIVE_STORY;
        }
        return StoryIllustrationIntent.NONE;
    }
}
