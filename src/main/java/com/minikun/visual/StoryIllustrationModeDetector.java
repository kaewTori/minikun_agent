package com.minikun.visual;

import java.util.Locale;
import java.util.regex.Pattern;

/** Selects an illustration treatment from explicit Thai or English wording. */
public final class StoryIllustrationModeDetector {
    private static final Pattern STORYBOARD = Pattern.compile(
            "(?:storyboard|story board|comic panels?|sequence of images|multiple scenes|"
                    + "สตอรีบอร์ด|ลำดับภาพ|หลายฉาก|แต่ละฉาก|ภาพต่อเนื่อง|สามภาพ|3\\s*ภาพ)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COVER = Pattern.compile(
            "(?:cover art|book cover|story cover|ภาพปก|ปกเรื่อง|ปกนิทาน|ปกหนังสือ)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PORTRAIT = Pattern.compile(
            "(?:character portrait|portrait|character sheet|ภาพตัวละคร|ภาพพอร์ตเทรต|พอร์ตเทรต|คาแรกเตอร์ชีต)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENDING = Pattern.compile(
            "(?:ending scene|final scene|last scene|ฉากจบ|ฉากสุดท้าย|ภาพตอนจบ|ภาพจบเรื่อง)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public StoryIllustrationMode detect(String userMessage) {
        String value = userMessage == null ? "" : userMessage.strip().toLowerCase(Locale.ROOT);
        if (STORYBOARD.matcher(value).find()) return StoryIllustrationMode.STORYBOARD;
        if (COVER.matcher(value).find()) return StoryIllustrationMode.COVER;
        if (PORTRAIT.matcher(value).find()) return StoryIllustrationMode.CHARACTER_PORTRAIT;
        if (ENDING.matcher(value).find()) return StoryIllustrationMode.ENDING_SCENE;
        return StoryIllustrationMode.DECISIVE_SCENE;
    }
}
