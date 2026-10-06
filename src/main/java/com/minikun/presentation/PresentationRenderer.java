package com.minikun.presentation;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.poi.common.usermodel.fonts.FontGroup;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.Insets2D;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.TextShape.TextAutofit;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import com.minikun.visual.GeneratedImageStore;

/** Renders the model's bounded design choices as editable PowerPoint objects. */
final class PresentationRenderer {
    static final int WIDTH = 960;
    static final int HEIGHT = 540;
    private static final String FONT = "Sarabun";
    private static final java.util.regex.Pattern LOCAL_IMAGE = java.util.regex.Pattern.compile(
            "^/v1/images/generated/([0-9a-f-]{36})\\.(png|jpg)$");
    private final GeneratedImageStore images;

    PresentationRenderer(GeneratedImageStore images) {
        this.images = images;
    }

    RenderedPresentation render(PresentationSpec spec) {
        List<String> warnings = new ArrayList<>();
        try (XMLSlideShow deck = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            deck.setPageSize(new Dimension(WIDTH, HEIGHT));
            PresentationTheme theme = PresentationTheme.named(spec.theme());
            for (int index = 0; index < spec.slides().size(); index++) {
                PresentationSpec.SlideSpec content = spec.slides().get(index);
                XSLFSlide slide = deck.createSlide();
                paintBackground(slide, theme);
                paintSlide(deck, slide, content, theme, index + 1, spec.slides().size(), warnings);
                addNotes(deck, slide, content, index + 1, spec.slides().size());
            }
            deck.write(output);
            return new RenderedPresentation(output.toByteArray(), List.copyOf(warnings));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("PowerPoint could not be created", exception);
        }
    }

    private void paintBackground(XSLFSlide slide, PresentationTheme theme) {
        var background = slide.getBackground();
        background.setFillColor(theme.background);
    }

    private void paintSlide(XMLSlideShow deck, XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme,
            int page, int pageCount, List<String> warnings) {
        String layout = content.layout();
        if ("cover".equals(layout)) {
            try {
                int titleWidth = content.imageUrl().isBlank() ? 540 : 460;
                addRule(slide, 80, 138, 7, theme.accent, 46);
                addText(slide, content.title(), rect(108, 96, titleWidth, 208), 44, theme.ink, true, false);
                addRule(slide, 108, 316, 84, theme.accent, 4);
                addTextTop(slide, content.body(), rect(108, 336, titleWidth, 132), 24, theme.muted, false);
                if (!content.imageUrl().isBlank()) {
                    addPanel(slide, rect(600, 90, 310, 360), theme.divider);
                    addImage(deck, slide, content.imageUrl(), rect(612, 102, 286, 336), warnings);
                } else {
                    coverMotif(slide, theme);
                }
                addPageNumber(slide, page, pageCount, theme);
                return;
            } catch (IllegalArgumentException textDoesNotFit) {
                removeShapesAfter(slide, 0);
                layout = "editorial";
            }
        }

        addRule(slide, 58, 45, 5, theme.accent, 38);
        addText(slide, content.title(), rect(78, 28, 804, 78), 34, theme.ink, true, false);
        addRule(slide, 78, 108, 804, theme.divider, 1);

        switch (layout) {
            case "editorial" -> editorial(deck, slide, content, theme, warnings);
            case "split", "cards" -> {
                int existingShapes = slide.getShapes().size();
                try {
                    if ("split".equals(layout)) split(deck, slide, content, theme, warnings);
                    else cards(slide, content, theme);
                } catch (IllegalArgumentException textDoesNotFit) {
                    removeShapesAfter(slide, existingShapes);
                    editorial(deck, slide, content, theme, warnings);
                }
            }
            case "comparison" -> comparison(slide, content, theme);
            case "stat" -> stat(slide, content, theme);
            case "quote" -> quote(slide, content, theme);
            case "timeline" -> timeline(slide, content, theme);
            default -> throw new IllegalArgumentException("unsupported slide layout");
        }
        addPageNumber(slide, page, pageCount, theme);
    }

    private void editorial(XMLSlideShow deck, XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme,
            List<String> warnings) {
        int bulletsY = 130;
        if (!content.body().isBlank()) {
            int width = content.imageUrl().isBlank() ? 780 : 490;
            var body = addTextTop(slide, content.body(), rect(92, 130, width, 358), 24, theme.ink, false);
            int bodyHeight = (int) Math.ceil(body.getTextHeight()) + 2;
            body.setAnchor(rect(92, 130, width, bodyHeight));
            addRule(slide, 72, 138, 4, theme.accent, Math.max(20, bodyHeight - 8));
            bulletsY += bodyHeight + 16;
        }
        addBullets(slide, content.bullets(), 74, bulletsY,
                content.imageUrl().isBlank() ? 800 : 500, 488 - bulletsY, theme);
        if (!content.imageUrl().isBlank()) {
            addPanel(slide, rect(608, 142, 294, 302), theme.divider);
            addImage(deck, slide, content.imageUrl(), rect(618, 152, 274, 282), warnings);
        }
    }

    private void split(XMLSlideShow deck, XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme,
            List<String> warnings) {
        if (content.imageUrl().isBlank()) {
            addPanel(slide, rect(66, 142, 398, 302), theme.divider);
            addRule(slide, 90, 165, 48, theme.accent, 4);
            addTextTop(slide, content.body(), rect(90, 186, 350, 240), 24, theme.ink, false);
            addBullets(slide, content.bullets(), 506, 154, 366, 280, theme);
            return;
        }
        addTextTop(slide, content.body(), rect(72, 152, 454, 116), 24, theme.ink, false);
        addBullets(slide, content.bullets(), 76, 284, 440, 164, theme);
        addPanel(slide, rect(572, 142, 330, 302), theme.divider);
        addImage(deck, slide, content.imageUrl(), rect(584, 154, 306, 278), warnings);
    }

    private void comparison(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        int existingShapes = slide.getShapes().size();
        try {
            addPanel(slide, rect(66, 142, 396, 302), theme.divider);
            addPanel(slide, rect(498, 142, 396, 302), theme.divider);
            addRule(slide, 88, 161, 44, theme.accent, 4);
            addRule(slide, 520, 161, 44, theme.accent, 4);
            addText(slide, content.leftTitle(), rect(88, 172, 344, 56), 26, theme.ink, true, false);
            addText(slide, content.rightTitle(), rect(520, 172, 344, 56), 26, theme.ink, true, false);
            addBullets(slide, content.leftBullets(), 88, 236, 350, 198, theme);
            addBullets(slide, content.rightBullets(), 520, 236, 350, 198, theme);
        } catch (IllegalArgumentException textDoesNotFit) {
            removeShapesAfter(slide, existingShapes);
            List<String> points = new ArrayList<>();
            content.leftBullets().forEach(point -> points.add(content.leftTitle() + ": " + point));
            content.rightBullets().forEach(point -> points.add(content.rightTitle() + ": " + point));
            addBullets(slide, points, 74, 142, 800, 318, theme);
        }
    }

    private void removeShapesAfter(XSLFSlide slide, int index) {
        for (var shape : List.copyOf(slide.getShapes()).subList(index, slide.getShapes().size())) {
            slide.removeShape(shape);
        }
    }

    private void cards(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        if (!content.body().isBlank()) addTextTop(slide, content.body(), rect(72, 130, 812, 54), 24, theme.muted, false);
        List<String> items = content.bullets();
        if (items.isEmpty()) return;
        int columns = items.size() == 1 ? 1 : 2;
        int rows = (items.size() + columns - 1) / columns;
        int top = content.body().isBlank() ? 145 : 196;
        int usableHeight = 458 - top;
        int gap = 16;
        int cardWidth = (828 - gap * (columns - 1)) / columns;
        int cardHeight = (usableHeight - gap * (rows - 1)) / rows;
        for (int index = 0; index < items.size(); index++) {
            int row = index / columns;
            int column = index % columns;
            int x = items.size() % 2 == 1 && columns == 2 && row == rows - 1 && column == 0
                    ? (WIDTH - cardWidth) / 2 : 66 + column * (cardWidth + gap);
            int y = top + row * (cardHeight + gap);
            addPanel(slide, rect(x, y, cardWidth, cardHeight), theme.divider);
            addDot(slide, x + 19, y + (cardHeight - 12) / 2, theme.accent);
            addText(slide, items.get(index), rect(x + 48, y + 12, cardWidth - 64, cardHeight - 24),
                    24, theme.ink, false, false);
        }
    }

    private void stat(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        addPanel(slide, rect(68, 144, 824, 298), theme.divider);
        addRule(slide, 96, 176, 6, theme.accent, 194);
        addText(slide, content.value(), rect(132, 158, 420, 124), 72, theme.accent, true, false);
        addText(slide, content.valueLabel(), rect(136, 282, 680, 60), 28, theme.ink, true, false);
        addTextTop(slide, content.body(), rect(136, 350, 690, 96), 24, theme.muted, false);
        addDot(slide, 742, 168, theme.accent, 112);
        addDot(slide, 772, 198, theme.divider, 52);
    }

    private void quote(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        addPanel(slide, rect(70, 144, 820, 300), theme.divider);
        addText(slide, "“", rect(96, 148, 42, 80), 44, theme.accent, true, false);
        addText(slide, content.quote(), rect(142, 202, 690, 150), 29, theme.ink, true, false);
        addRule(slide, 144, 372, 48, theme.accent, 3);
        addText(slide, content.attribution(), rect(208, 376, 606, 64), 24, theme.muted, false, false);
    }

    private void timeline(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        List<PresentationSpec.TimelinePoint> points = content.timeline();
        int rowHeight = 310 / points.size();
        if (points.size() > 1) addRule(slide, 85, 157, 2, theme.divider, rowHeight * (points.size() - 1));
        for (int index = 0; index < points.size(); index++) {
            var point = points.get(index);
            int y = 142 + index * rowHeight;
            addDot(slide, 79, y + 10, theme.accent);
            addTextTop(slide, point.label(), rect(112, y, 210, rowHeight - 8), 24, theme.accent, true);
            addTextTop(slide, point.text(), rect(342, y, 536, rowHeight - 8), 24, theme.ink, false);
        }
    }

    private void addBullets(XSLFSlide slide, List<String> bullets, int x, int y, int width, int height,
            PresentationTheme theme) {
        if (bullets.isEmpty()) return;
        int gap = 16;
        int rowY = y;
        for (int index = 0; index < bullets.size(); index++) {
            addDot(slide, x + 2, rowY + 13, theme.accent, 10);
            var text = addTextTop(slide, bullets.get(index), rect(x + 24, rowY, width - 24, y + height - rowY),
                    24, theme.ink, false);
            int textHeight = (int) Math.ceil(text.getTextHeight()) + 2;
            text.setAnchor(rect(x + 24, rowY, width - 24, textHeight));
            rowY += textHeight + gap;
        }
    }

    private void coverMotif(XSLFSlide slide, PresentationTheme theme) {
        addDot(slide, 650, 128, theme.accent, 220);
        addDot(slide, 680, 158, theme.background, 160);
        addDot(slide, 786, 310, theme.divider, 72);
        addRule(slide, 691, 411, 154, theme.divider, 7);
    }

    private void addPanel(XSLFSlide slide, Rectangle bounds, Color color) {
        XSLFAutoShape panel = slide.createAutoShape();
        panel.setShapeType(ShapeType.ROUND_RECT);
        panel.setAnchor(bounds);
        panel.setFillColor(color);
        panel.setLineColor(color);
    }

    private void addDot(XSLFSlide slide, int x, int y, Color color, int diameter) {
        XSLFAutoShape dot = slide.createAutoShape();
        dot.setShapeType(ShapeType.ELLIPSE);
        dot.setAnchor(rect(x, y, diameter, diameter));
        dot.setFillColor(color);
        dot.setLineColor(color);
    }

    private void addImage(XMLSlideShow deck, XSLFSlide slide, String path, Rectangle bounds, List<String> warnings) {
        var match = LOCAL_IMAGE.matcher(path);
        if (images == null || !match.matches()) {
            warnings.add("ข้ามภาพประกอบที่ไม่มีไฟล์ภาพภายในเครื่อง");
            return;
        }
        try {
            var stored = images.read(match.group(1) + "." + match.group(2));
            PictureType type = "png".equals(match.group(2)) ? PictureType.PNG : PictureType.JPEG;
            var pictureData = deck.addPicture(stored.bytes(), type);
            var picture = slide.createPicture(pictureData);
            Dimension size = picture.getPictureData().getImageDimension();
            double scale = Math.min(bounds.getWidth() / size.width, bounds.getHeight() / size.height);
            double width = size.width * scale;
            double height = size.height * scale;
            picture.setAnchor(rect((int) (bounds.x + (bounds.width - width) / 2),
                    (int) (bounds.y + (bounds.height - height) / 2), (int) width, (int) height));
        } catch (RuntimeException exception) {
            warnings.add("ข้ามภาพประกอบที่อ่านไม่สำเร็จ");
        }
    }

    private void addNotes(XMLSlideShow deck, XSLFSlide slide, PresentationSpec.SlideSpec content,
            int page, int pageCount) {
        StringBuilder notes = new StringBuilder("Slide ").append(page).append(" of ").append(pageCount);
        if (!content.speakerNotes().isBlank()) notes.append("\n\n").append(content.speakerNotes());
        if (!content.sources().isEmpty()) notes.append("\n\nSources:\n- ")
                .append(String.join("\n- ", content.sources()));
        XSLFNotes notesSlide = deck.getNotesSlide(slide);
        XSLFTextShape body = (XSLFTextShape) notesSlide.getPlaceholder(Placeholder.BODY);
        if (body != null) body.setText(notes.toString());
    }

    private void addPageNumber(XSLFSlide slide, int page, int count, PresentationTheme theme) {
        addRule(slide, 64, 504, 832, theme.divider, 1);
        addText(slide, String.format(Locale.ROOT, "%02d / %02d", page, count),
                rect(806, 510, 90, 20), 11, theme.muted, false, true);
    }

    private void addRule(XSLFSlide slide, int x, int y, int width, Color color, int thickness) {
        XSLFAutoShape rule = slide.createAutoShape();
        rule.setShapeType(ShapeType.RECT);
        rule.setAnchor(rect(x, y, Math.max(width, 1), thickness));
        rule.setFillColor(color);
        rule.setLineColor(color);
    }

    private void addDot(XSLFSlide slide, int x, int y, Color color) {
        XSLFAutoShape dot = slide.createAutoShape();
        dot.setShapeType(ShapeType.ELLIPSE);
        dot.setAnchor(rect(x, y, 14, 14));
        dot.setFillColor(color);
        dot.setLineColor(color);
    }

    private void addText(XSLFSlide slide, String text, Rectangle2D bounds, int size, Color color,
            boolean bold, boolean center) {
        addText(slide, text, bounds, size, color, bold, center, VerticalAlignment.MIDDLE);
    }

    private XSLFTextBox addTextTop(XSLFSlide slide, String text, Rectangle2D bounds, int size, Color color,
            boolean bold) {
        return addTextTop(slide, text, bounds, size, color, bold, false);
    }

    private XSLFTextBox addTextTop(XSLFSlide slide, String text, Rectangle2D bounds, int size, Color color,
            boolean bold, boolean center) {
        return addText(slide, text, bounds, size, color, bold, center, VerticalAlignment.TOP);
    }

    private XSLFTextBox addText(XSLFSlide slide, String text, Rectangle2D bounds, int size, Color color,
            boolean bold, boolean center, VerticalAlignment verticalAlignment) {
        if (text == null || text.isBlank()) return null;
        XSLFTextBox box = textBox(slide, bounds, verticalAlignment);
        box.clearText();
        boolean bodyText = size >= 24 && size < 32;
        for (String block : text.split("\\R[\\t ]*\\R", -1)) {
            XSLFTextParagraph paragraph = box.addNewTextParagraph();
            paragraph.setLineSpacing(-size * (bodyText ? 1.6 : 1.35));
            paragraph.setSpaceAfter(bodyText ? 8.0 : 0.0);
            if (center) paragraph.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER);
            String[] lines = block.split("\\R", -1);
            for (int index = 0; index < lines.length; index++) {
                if (index > 0) style(paragraph.addLineBreak(), size, color, bold);
                XSLFTextRun run = paragraph.addNewTextRun();
                run.setText(lines[index]);
                style(run, size, color, bold);
            }
        }
        if (box.getTextHeight() > bounds.getHeight() + 2) {
            throw new IllegalArgumentException("Text on slide " + (slide.getSlideNumber())
                    + " does not fit at " + size + "pt: '" + text.substring(0, Math.min(text.length(), 70))
                    + "'. Shorten the wording or use a wider layout; keep key examples visible and move only "
                    + "supporting detail to speakerNotes.");
        }
        return box;
    }

    private XSLFTextBox textBox(XSLFSlide slide, Rectangle2D bounds, VerticalAlignment verticalAlignment) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(bounds);
        box.setInsets(new Insets2D(0, 0, 0, 0));
        box.setWordWrap(true);
        box.setVerticalAlignment(verticalAlignment);
        box.setTextAutofit(TextAutofit.NONE);
        return box;
    }

    private void style(XSLFTextRun run, int size, Color color, boolean bold) {
        run.setFontFamily(FONT);
        run.setFontFamily(FONT, FontGroup.COMPLEX_SCRIPT);
        run.setFontSize((double) size);
        run.setFontColor(color);
        run.setBold(bold);
    }

    private static Rectangle rect(int x, int y, int width, int height) {
        return new Rectangle(x, y, width, height);
    }

    record RenderedPresentation(byte[] bytes, List<String> warnings) { }
}
