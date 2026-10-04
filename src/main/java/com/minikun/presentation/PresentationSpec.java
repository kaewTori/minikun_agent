package com.minikun.presentation;

import java.util.List;
import java.util.Locale;

record PresentationSpec(String title, String language, String theme, List<SlideSpec> slides) {
    static final int MAX_SLIDES = 20;
    static final int MAX_SPEC_CHARACTERS = 100_000;

    PresentationSpec {
        slides = slides == null ? List.of() : List.copyOf(slides);
        title = optional(title, 160);
        if (title.isBlank()) title = slides.isEmpty() ? "PowerPoint" : slides.getFirst().title();
        title = required(title, "presentation title", 160);
        language = optional(language, 32);
        theme = optional(theme, 32).toLowerCase(Locale.ROOT);
        if (!List.of("japanese", "paper", "ocean", "midnight").contains(theme)) {
            theme = "paper";
        }
        if (slides.isEmpty() || slides.size() > MAX_SLIDES) {
            throw new IllegalArgumentException("presentation must contain 1 to " + MAX_SLIDES + " slides");
        }
        List<String> slideImages = slides.stream().map(SlideSpec::imageUrl).filter(value -> !value.isBlank()).toList();
        if (slideImages.size() > 4 || slideImages.stream().distinct().count() != slideImages.size()) {
            throw new IllegalArgumentException("use up to 4 distinct generated images, once each");
        }
    }

    record SlideSpec(
            String title,
            String layout,
            String body,
            List<String> bullets,
            String leftTitle,
            List<String> leftBullets,
            String rightTitle,
            List<String> rightBullets,
            String value,
            String valueLabel,
            String quote,
            String attribution,
            List<TimelinePoint> timeline,
            String imageUrl,
            String speakerNotes,
            List<String> sources) {
        SlideSpec {
            title = optional(title, 160);
            if (title.isBlank()) title = defaultSlideTitle(body, bullets);
            title = required(title, "slide title", 160);
            layout = optional(layout, 24).toLowerCase(Locale.ROOT);
            body = optional(body, 2_000);
            bullets = bounded(bullets, 5, 280, "bullets");
            leftTitle = optional(leftTitle, 120);
            leftBullets = bounded(leftBullets, 5, 280, "left bullets");
            rightTitle = optional(rightTitle, 120);
            rightBullets = bounded(rightBullets, 5, 280, "right bullets");
            value = optional(value, 80);
            valueLabel = optional(valueLabel, 240);
            quote = optional(quote, 1_000);
            attribution = optional(attribution, 160);
            timeline = timeline == null ? List.of() : List.copyOf(timeline);
            if (timeline.size() > 5) throw new IllegalArgumentException("timeline supports at most 5 points");
            imageUrl = optional(imageUrl, 160);
            speakerNotes = optional(speakerNotes, 4_000);
            sources = bounded(sources, 8, 400, "sources");
            if (!List.of("cover", "editorial", "split", "comparison", "cards", "stat", "quote", "timeline")
                    .contains(layout)) {
                throw new IllegalArgumentException("unsupported slide layout: " + layout);
            }
            if ("comparison".equals(layout)) {
                if (leftTitle.isBlank()) leftTitle = "ตัวเลือก A";
                if (rightTitle.isBlank()) rightTitle = "ตัวเลือก B";
            }
            if (("stat".equals(layout) && value.isBlank())
                    || ("quote".equals(layout) && quote.isBlank())
                    || ("timeline".equals(layout) && timeline.isEmpty())) {
                layout = "editorial";
            }
        }
    }

    record TimelinePoint(String label, String text) {
        TimelinePoint {
            label = required(label, "timeline label", 100);
            text = required(text, "timeline text", 240);
        }
    }

    private static String required(String value, String name, int maximum) {
        String result = optional(value, maximum);
        if (result.isBlank()) throw new IllegalArgumentException(name + " is required");
        return result;
    }

    private static String optional(String value, int maximum) {
        String result = value == null ? "" : value.strip();
        if (result.length() > maximum) throw new IllegalArgumentException("slide content exceeds its length limit");
        return result;
    }

    private static String defaultSlideTitle(String body, List<String> bullets) {
        String candidate = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        if (candidate.isBlank() && bullets != null && !bullets.isEmpty()) {
            candidate = bullets.getFirst() == null ? "" : bullets.getFirst().strip().replaceAll("\\s+", " ");
        }
        if (candidate.isBlank()) return "ประเด็นสำคัญ";
        int sentenceEnd = candidate.indexOf('。');
        if (sentenceEnd < 0) sentenceEnd = candidate.indexOf('.');
        if (sentenceEnd > 0 && sentenceEnd < 80) candidate = candidate.substring(0, sentenceEnd);
        return candidate.length() <= 80 ? candidate : candidate.substring(0, 77).stripTrailing() + "…";
    }

    private static List<String> bounded(List<String> values, int maximumCount, int maximumLength, String name) {
        if (values == null) return List.of();
        if (values.size() > maximumCount) throw new IllegalArgumentException(name + " exceeds its item limit");
        return values.stream().map(value -> required(value, name, maximumLength)).toList();
    }
}
