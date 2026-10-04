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
        if ("cover".equals(content.layout())) {
            int titleWidth = content.imageUrl().isBlank() ? 540 : 460;
            addRule(slide, 80, 138, 7, theme.accent, 46);
            addText(slide, content.title(), rect(108, 142, titleWidth, 126), 40, theme.ink, true, false);
            addRule(slide, 108, 286, 84, theme.accent, 4);
            addTextTop(slide, content.body(), rect(108, 311, titleWidth, 88), 22, theme.muted, false);
            if (!content.imageUrl().isBlank()) {
                addPanel(slide, rect(600, 90, 310, 360), theme.divider);
                addImage(deck, slide, content.imageUrl(), rect(612, 102, 286, 336), warnings);
            } else {
                coverMotif(slide, theme);
            }
            addPageNumber(slide, page, pageCount, theme);
            return;
        }

        addRule(slide, 58, 45, 5, theme.accent, 38);
        addText(slide, content.title(), rect(78, 36, 804, 58), 28, theme.ink, true, false);
        addRule(slide, 78, 108, 804, theme.divider, 1);

        switch (content.layout()) {
            case "editorial" -> editorial(deck, slide, content, theme, warnings);
            case "split" -> split(deck, slide, content, theme, warnings);
            case "comparison" -> comparison(slide, content, theme);
            case "cards" -> cards(slide, content, theme);
            case "stat" -> stat(slide, content, theme);
            case "quote" -> quote(slide, content, theme);
            case "timeline" -> timeline(slide, content, theme);
            default -> throw new IllegalArgumentException("unsupported slide layout");
        }
        addPageNumber(slide, page, pageCount, theme);
    }

    private void editorial(XMLSlideShow deck, XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme,
            List<String> warnings) {
        if (!content.body().isBlank()) {
            addRule(slide, 72, 150, 4, theme.accent, content.bullets().isEmpty() ? 122 : 76);
            addTextTop(slide, content.body(), rect(92, 142, content.imageUrl().isBlank() ? 780 : 490,
                    content.bullets().isEmpty() ? 230 : 104), 22, theme.ink, false);
        }
        addBullets(slide, content.bullets(), 74, content.body().isBlank() ? 142 : 278,
                content.imageUrl().isBlank() ? 800 : 500, content.body().isBlank() ? 306 : 174, theme);
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
            addTextTop(slide, content.body(), rect(90, 186, 350, 228), 23, theme.ink, false);
            addBullets(slide, content.bullets(), 506, 154, 366, 280, theme);
            return;
        }
        addTextTop(slide, content.body(), rect(72, 152, 454, 224), 23, theme.ink, false);
        addBullets(slide, content.bullets(), 76, 300, 440, 148, theme);
        addPanel(slide, rect(572, 142, 330, 302), theme.divider);
        addImage(deck, slide, content.imageUrl(), rect(584, 154, 306, 278), warnings);
    }

    private void comparison(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        addPanel(slide, rect(66, 142, 396, 302), theme.divider);
        addPanel(slide, rect(498, 142, 396, 302), theme.divider);
        addRule(slide, 88, 161, 44, theme.accent, 4);
        addRule(slide, 520, 161, 44, theme.accent, 4);
        addText(slide, content.leftTitle(), rect(88, 174, 344, 42), 22, theme.ink, true, false);
        addText(slide, content.rightTitle(), rect(520, 174, 344, 42), 22, theme.ink, true, false);
        addBullets(slide, content.leftBullets(), 88, 225, 350, 198, theme);
        addBullets(slide, content.rightBullets(), 520, 225, 350, 198, theme);
    }

    private void cards(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        if (!content.body().isBlank()) addTextTop(slide, content.body(), rect(72, 130, 812, 54), 19, theme.muted, false);
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
                    19, theme.ink, false, false);
        }
    }

    private void stat(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        addPanel(slide, rect(68, 144, 824, 298), theme.divider);
        addRule(slide, 96, 176, 6, theme.accent, 194);
        addText(slide, content.value(), rect(132, 158, 420, 124), 72, theme.accent, true, false);
        addText(slide, content.valueLabel(), rect(136, 292, 680, 48), 25, theme.ink, true, false);
        addTextTop(slide, content.body(), rect(136, 352, 690, 64), 18, theme.muted, false);
        addDot(slide, 742, 168, theme.accent, 112);
        addDot(slide, 772, 198, theme.divider, 52);
    }

    private void quote(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        addPanel(slide, rect(70, 144, 820, 300), theme.divider);
        addText(slide, "“", rect(102, 156, 74, 70), 64, theme.accent, true, false);
        addText(slide, content.quote(), rect(142, 202, 690, 150), 29, theme.ink, true, false);
        addRule(slide, 144, 372, 48, theme.accent, 3);
        addText(slide, content.attribution(), rect(144, 389, 670, 30), 17, theme.muted, false, false);
    }

    private void timeline(XSLFSlide slide, PresentationSpec.SlideSpec content, PresentationTheme theme) {
        List<PresentationSpec.TimelinePoint> points = content.timeline();
        int startX = 105, endX = 855;
        int gap = points.size() == 1 ? 0 : (endX - startX) / (points.size() - 1);
        if (points.size() > 1) addRule(slide, startX, 203, endX - startX, theme.divider, 2);
        for (int index = 0; index < points.size(); index++) {
            var point = points.get(index);
            int x = startX + gap * index;
            addDot(slide, x - 7, 196, theme.accent);
            addPanel(slide, rect(x - 75, 228, 150, 188), theme.divider);
            addText(slide, point.label(), rect(x - 64, 240, 128, 34), 18, theme.accent, true, true);
            addTextTop(slide, point.text(), rect(x - 63, 283, 126, 118), 16, theme.ink, false, true);
        }
    }

    private void addBullets(XSLFSlide slide, List<String> bullets, int x, int y, int width, int height,
            PresentationTheme theme) {
        if (bullets.isEmpty()) return;
        int gap = bullets.size() > 3 ? 4 : 13;
        int rowHeight = Math.min(52, (height - gap * (bullets.size() - 1)) / bullets.size());
        int topInset = (height - rowHeight * bullets.size() - gap * (bullets.size() - 1)) / 2;
        int fontSize = rowHeight < 29 ? 16 : rowHeight < 38 ? 17 : 18;
        for (int index = 0; index < bullets.size(); index++) {
            int rowY = y + topInset + index * (rowHeight + gap);
            addDot(slide, x + 2, rowY + 13, theme.accent, 10);
            addTextTop(slide, bullets.get(index), rect(x + 24, rowY, width - 24, rowHeight),
                    fontSize, theme.ink, false);
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
        addRule(slide, 64, 476, 832, theme.divider, 1);
        addText(slide, String.format(Locale.ROOT, "%02d / %02d", page, count),
                rect(806, 482, 90, 24), 11, theme.muted, false, true);
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

    private void addTextTop(XSLFSlide slide, String text, Rectangle2D bounds, int size, Color color,
            boolean bold) {
        addTextTop(slide, text, bounds, size, color, bold, false);
    }

    private void addTextTop(XSLFSlide slide, String text, Rectangle2D bounds, int size, Color color,
            boolean bold, boolean center) {
        addText(slide, text, bounds, size, color, bold, center, VerticalAlignment.TOP);
    }

    private void addText(XSLFSlide slide, String text, Rectangle2D bounds, int size, Color color,
            boolean bold, boolean center, VerticalAlignment verticalAlignment) {
        if (text == null || text.isBlank()) return;
        XSLFTextBox box = textBox(slide, bounds, verticalAlignment);
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        if (center) paragraph.setTextAlign(org.apache.poi.sl.usermodel.TextParagraph.TextAlign.CENTER);
        XSLFTextRun run = paragraph.addNewTextRun();
        run.setText(text);
        style(run, size, color, bold);
    }

    private XSLFTextBox textBox(XSLFSlide slide, Rectangle2D bounds, VerticalAlignment verticalAlignment) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(bounds);
        box.setInsets(new Insets2D(0, 0, 0, 0));
        box.setWordWrap(true);
        box.setVerticalAlignment(verticalAlignment);
        box.setTextAutofit(TextAutofit.NORMAL);
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
