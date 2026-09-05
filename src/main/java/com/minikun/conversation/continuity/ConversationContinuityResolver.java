package com.minikun.conversation.continuity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative cross-turn resolver shared by retrieval and prompt composition. */
public final class ConversationContinuityResolver {
    private static final int MAX_TOPIC_CHARACTERS = 240;
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern LATIN_ENTITY = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9.+#_-]*(?:\\s+(?:[a-z][A-Za-z0-9.+#_-]*|"
                    + "[A-Z0-9][A-Za-z0-9.+#_-]*)){0,3})\\b");
    private static final Pattern LABELLED_LATIN_ENTITY = Pattern.compile(
            "(?iu)(?:ชื่อ|เกี่ยวกับ|เรื่อง|named|about)\\s+[\\\"'“”]?"
                    + "([A-Za-z0-9][A-Za-z0-9._+#-]*(?:\\s+[A-Za-z0-9][A-Za-z0-9._+#-]*){0,3})");
    private static final Pattern QUOTED_ENTITY = Pattern.compile("[\\\"“]([^\\\"”]{2,80})[\\\"”]");
    private static final Pattern TOPIC_DEPENDENT = Pattern.compile(
            "(?iu)^(?:(?:แล้ว|งั้น|ถ้าอย่างนั้น|ส่วน|and|so|then|what about|how about)\\s*)?"
                    + "(?:(?:มี|ขอ|ช่วยหา|ช่วยแนะนำ|อยากดู|เอา)?\\s*(?:รูป|ภาพ|ผลงาน|ตัวอย่าง|ลิงก์|"
                    + "รายละเอียด|ข้อดี|ข้อเสีย|ราคา|สเปก|รุ่น)|(?:show|find|recommend|give)\\s+(?:me\\s+)?"
                    + "(?:more\\s+)?(?:images?|pictures?|photos?|works?|examples?|links?|details?))");
    private static final Pattern VISUAL_REQUEST = Pattern.compile(
            "(?iu)(?:รูป|ภาพ|ผลงาน|แกลเลอรี|reference|images?|pictures?|photos?|artworks?|works?)");
    private static final Pattern EXPLICIT_SUBJECT = Pattern.compile(
            "(?iu)(?:รูป|ภาพ|ผลงาน|ตัวอย่าง|ลิงก์|รายละเอียด)(?:ของ|\\s+(?:of|by|from)\\b)");
    private static final Pattern CONTEXT_ACTION = Pattern.compile(
            "(?iu)^(?:ช่วย\\s*)?(?:ค้น(?:หา)?(?:\\s*ข้อมูล)?|หา\\s*ข้อมูล|เช็ก|เช็ค|ตรวจ(?:สอบ|ดู)?)"
                    + "(?:\\s*(?:เรื่อง)?(?:เมื่อกี้|ก่อนหน้า|นั้น|นี้))?(?:\\s*(?:ให้|หน่อย|ให้หน่อย))*[?!.。！？]*$");
    private static final Pattern RESPONSE_REVISION = Pattern.compile(
            "(?iu)^(?:มัน|คำตอบ|เรื่อง|เนื้อหา)?\\s*(?:สั้น|ยาว|เร็ว|ช้า|ละเอียด|เยอะ|น้อย)"
                    + "(?:ไป|เกิน|ขึ้น|ลง|กว่านี้|อีก|หน่อย)");
    private static final List<String> FOLLOW_UP_MARKERS = List.of(
            "อีก", "แล้ว", "ล่ะ", "อันนี้", "ตัวนี้", "คนนี้", "เรื่องนี้", "แบบนี้", "สิ่งนี้",
            "เรื่องเมื่อกี้", "เมื่อกี้", "ก่อนหน้านี้", "ดังกล่าว", "ของเขา", "ของเธอ", "ต่อ", "เพิ่มเติม",
            "what about", "how about",
            "and this", "that one", "this one", "more", "also", "continue");
    private static final Set<String> IGNORED_ENTITIES = Set.of(
            "please", "search", "find", "show", "image", "images", "picture", "pictures",
            "photo", "photos", "what", "how", "user", "assistant");

    public ConversationContinuity resolve(String latestMessage, String conversationContext) {
        String latest = normalize(latestMessage);
        String previous = lastUserMessage(conversationContext);
        if (latest.isBlank() || previous.isBlank() || !isFollowUp(latest)) {
            return ConversationContinuity.NONE;
        }

        List<String> currentEntities = extractEntities(latest);
        List<String> previousEntities = extractEntities(previous);
        boolean currentNamesSubject = EXPLICIT_SUBJECT.matcher(latest).find()
                || LABELLED_LATIN_ENTITY.matcher(latest).find();
        List<String> resolvedEntities = currentNamesSubject && !currentEntities.isEmpty()
                ? currentEntities
                : !previousEntities.isEmpty() ? previousEntities : currentEntities;
        String anchor = !resolvedEntities.isEmpty()
                ? resolvedEntities.getFirst()
                : bounded(previous);
        String resolvedQuery = containsIgnoreCase(latest, anchor)
                ? latest
                : normalize(anchor + " " + latest);
        double confidence = !resolvedEntities.isEmpty() ? 0.94 : TOPIC_DEPENDENT.matcher(latest).find() ? 0.88 : 0.78;
        return new ConversationContinuity(
                true,
                bounded(previous),
                bounded(anchor),
                resolvedEntities,
                bounded(resolvedQuery),
                VISUAL_REQUEST.matcher(latest).find(),
                confidence);
    }

    private boolean isFollowUp(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return FOLLOW_UP_MARKERS.stream().anyMatch(lower::contains)
                || CONTEXT_ACTION.matcher(value).matches()
                || RESPONSE_REVISION.matcher(value).find()
                || !EXPLICIT_SUBJECT.matcher(value).find() && TOPIC_DEPENDENT.matcher(value).find()
                || value.length() <= 24
                        && lower.matches("^(ราคา|สเปก|รุ่น|เวอร์ชัน|ปีนี้|ตอนนี้|ปัจจุบัน|"
                                + "price|spec|version|release|current|latest)\\s*[?!.。！？]*$");
    }

    private List<String> extractEntities(String value) {
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        collectMatches(LABELLED_LATIN_ENTITY.matcher(value), entities);
        collectMatches(QUOTED_ENTITY.matcher(value), entities);
        collectMatches(LATIN_ENTITY.matcher(value), entities);
        return List.copyOf(entities);
    }

    private void collectMatches(Matcher matcher, Set<String> target) {
        while (matcher.find() && target.size() < 4) {
            String candidate = normalize(matcher.group(1));
            if (!candidate.isBlank() && !IGNORED_ENTITIES.contains(candidate.toLowerCase(Locale.ROOT))) {
                target.add(bounded(candidate));
            }
        }
    }

    private String lastUserMessage(String context) {
        if (context == null || context.isBlank()) {
            return "";
        }
        String[] lines = context.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].strip();
            if (line.regionMatches(true, 0, "user:", 0, 5)) {
                String value = normalize(line.substring(5));
                if (!value.isBlank()) {
                    return bounded(value);
                }
            }
        }
        return "";
    }

    private boolean containsIgnoreCase(String value, String part) {
        return !part.isBlank() && value.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    private String normalize(String value) {
        return value == null ? "" : SPACE.matcher(value).replaceAll(" ").strip();
    }

    private String bounded(String value) {
        String normalized = normalize(value);
        return normalized.length() <= MAX_TOPIC_CHARACTERS
                ? normalized
                : normalized.substring(0, MAX_TOPIC_CHARACTERS).stripTrailing();
    }
}
