package com.minikun.visual;

import com.minikun.model.CooperationRouter;
import com.minikun.research.ResearchIntentDetector;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministically detects image creation without confusing it with image search. */
public final class StoryIllustrationIntentDetector {
    private static final Pattern DIRECT_IMAGE = Pattern.compile(
            "(?:สร้าง|วาด|เจน|เจเนอเรต|ทำ|ออกแบบ|gen(?:erate)?|create|draw|make|design)"
                    + ".{0,40}(?:ภาพ|รูป|อิลลัส(?:เตรชัน)?|ภาพประกอบ|images?|pictures?|illustrations?|artwork)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern VISUAL_COMPANION = Pattern.compile(
            "(?:พร้อม|มี|ใส่|แทรก|แนบ|ประกอบ|เพิ่ม).{0,30}(?:ภาพ|รูป|อิลลัส(?:เตรชัน)?|ภาพประกอบ)|"
                    + "(?:ภาพ|รูป|ภาพประกอบ).{0,30}(?:ประกอบ|แต่ละฉาก|เรื่อง|นิทาน)|"
                    + "(?:with|include|add|accompanied by).{0,30}(?:images?|pictures?|illustrations?|artwork)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final CooperationRouter cooperationRouter = new CooperationRouter();
    private final ResearchIntentDetector narrativeDetector = new ResearchIntentDetector();

    public StoryIllustrationIntent detect(String message, boolean autoIllustrateCreativeStories) {
        String value = message == null ? "" : message.strip().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return StoryIllustrationIntent.NONE;
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
