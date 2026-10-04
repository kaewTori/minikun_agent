package com.minikun.visual;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Owns the layout; the model supplies only editorial content. */
final class InfographicRenderer {
    static final String POLICY = """
            Write concise content for one infographic. The application handles all visual design.
            Return exactly one JSON object with this schema:
            {"title":"Main title","subtitle":"Short introduction","cards":[
              {"heading":"Key point","body":"One or two short sentences"}],"summary":"Main takeaway"}.
            Use 1 to 6 cards, grouping related content without dropping requested details.
            The application adapts columns, text wrapping, box heights and canvas height to the content.
            Write in the user's language. Keep title under 70 characters, subtitle under 160,
            each heading under 80, each body under 400, and summary under 300 characters.
            Avoid numbering the cards.
            Use only numerical facts supplied by the user; never invent statistics.
            Do not add numerical digits absent from the request, including step counts in the title.
            Do not include coordinates, fonts, colors, SVG, HTML, markdown, or code.
            """;
    static final String FLOWCHART_POLICY = """
            Write concise content for a conventional flowchart. The application draws the symbols and arrows.
            Return exactly one JSON object: {"title":"Process title","steps":[
              {"type":"input","heading":"Receive request","body":"Short explanation"},
              {"type":"process","heading":"Process information","body":"Short explanation"},
              {"type":"output","heading":"Present result","body":"Short explanation"}]}.
            Allowed types: input (receive data), process (action), output (present data), decision (a real choice).
            The application adds start and end symbols; do not include them in steps.
            Preserve the original topic and steps, following the current request's changes.
            Use 1 to 5 steps, or at most 4 steps when there is a decision. At most one decision is supported.
            Only include a decision when the process actually contains a condition; never invent one for appearance.
            A decision step is {"type":"decision","heading":"Condition?","body":"",
              "yes":"Action when true","no":"Action when false"}; both actions rejoin the next step.
            The application wraps labels and expands symbols and canvas height to fit the content.
            Do not describe backward loops in this schema. Keep decision questions under 80 characters,
            branch actions under 100, other headings under 80, bodies under 180, and title under 70.
            Use the user's language. Do not add numeric digits absent from the request, including step numbers.
            Do not include coordinates, fonts, colors, elements, cards, SVG, HTML, markdown, or code.
            """;
    private static final FontRenderContext METRICS = new FontRenderContext(null, true, true);
    private static final Locale THAI = Locale.forLanguageTag("th");
    private static final String INK = "#18334D", BODY = "#40586D", BLUE = "#31648C", TEAL = "#147D86";

    private InfographicRenderer() { }

    static String render(JsonNode content) {
        JsonNode cards = content.path("cards");
        // ponytail: up to six cards in a measured grid; use pagination for larger infographics.
        if (!content.isObject() || !cards.isArray() || cards.isEmpty() || cards.size() > 6) {
            throw new ImageGenerationException("infographic requires 1 to 6 content cards");
        }
        Block title = fit(label(content, "title", 120), 1024, 48, 36, 2, true);
        Block subtitle = fit(label(content, "subtitle", 180), 1024, 24, 22, 3, false);
        Block summary = fit(label(content, "summary", 300), 1032, 24, 22, 6, false);
        int columns = Math.min(3, cards.size());
        while (columns > 1) {
            int width = (1104 - 24 * (columns - 1)) / columns - 56;
            boolean crowded = false;
            for (JsonNode card : cards) {
                if (wrap(label(card, "heading", 80), width, 28, true).size() > 2
                        || wrap(label(card, "body", 400), width, 24, false).size() > 6) crowded = true;
            }
            if (!crowded) break;
            columns--;
        }
        int cardWidth = (1104 - 24 * (columns - 1)) / columns;
        List<Block> headings = new ArrayList<>(), bodies = new ArrayList<>();
        int cardHeight = 0;
        for (JsonNode card : cards) {
            Block heading = fit(label(card, "heading", 80), cardWidth - 56, 28, 24, 3, true);
            Block body = fit(label(card, "body", 400), cardWidth - 56, 24, 22, 12, false);
            headings.add(heading);
            bodies.add(body);
            cardHeight = Math.max(cardHeight, 76 + heading.height() + 16 + body.height());
        }
        int cardTop = 84 + title.height() + 12 + subtitle.height() + 32;
        int summaryHeight = 72 + summary.height();
        int rows = (cards.size() + columns - 1) / columns;
        int summaryTop = cardTop + rows * cardHeight + (rows - 1) * 24 + 28;
        StringBuilder svg = new StringBuilder();
        rect(svg, 48, 40, 40, 5, 2, TEAL, null);
        rect(svg, 96, 40, 16, 5, 2, BLUE, null);
        text(svg, title, 48, 84, INK, true);
        text(svg, subtitle, 48, 84 + title.height() + 12, BODY, false);
        for (int index = 0; index < cards.size(); index++) {
            int x = 48 + (index % columns) * (cardWidth + 24);
            int y = cardTop + (index / columns) * (cardHeight + 24);
            String accent = index % 2 == 0 ? BLUE : TEAL;
            rect(svg, x, y, cardWidth, cardHeight, 20, "#FFFFFF", "#DCE6ED");
            rect(svg, x + 28, y + 28, 36, 6, 3, accent, null);
            text(svg, headings.get(index), x + 28, y + 54, accent, true);
            text(svg, bodies.get(index), x + 28, y + 54 + headings.get(index).height() + 16, BODY, false);
        }
        rect(svg, 48, summaryTop, 1104, summaryHeight, 20, INK, null);
        String caption = content.path("title").asText().matches("(?s).*\\p{IsThai}.*") ? "ใจความสำคัญ" : "KEY TAKEAWAY";
        text(svg, fit(caption, 1032, 18, 18, 1, true), 84, summaryTop + 18, "#9FDBDF", true);
        text(svg, summary, 84, summaryTop + 48, "#FFFFFF", false);
        return canvas(svg, summaryTop + summaryHeight + 48);
    }

    static String renderFlowchart(JsonNode content) {
        JsonNode steps = content.path("steps");
        if (!content.isObject() || !steps.isArray() || steps.isEmpty() || steps.size() > 5) {
            throw new ImageGenerationException("flowchart requires 1 to 5 steps");
        }
        int decisions = 0;
        for (JsonNode step : steps) {
            String type = step.path("type").asText("process");
            if (!type.matches("input|process|output|decision")) {
                throw new ImageGenerationException("flowchart step type is invalid");
            }
            if ("decision".equals(type)) decisions++;
            else if (step.has("yes") || step.has("no")) {
                throw new ImageGenerationException("flowchart branches require a decision step");
            }
        }
        // ponytail: five steps or four with one binary decision; use graph layout for larger or cyclic flows.
        if (decisions > 1 || decisions > 0 && steps.size() > 4) {
            throw new ImageGenerationException("flowchart supports one binary decision and at most four steps with it");
        }
        boolean thai = content.toString().matches("(?s).*\\p{IsThai}.*");
        Block title = fit(label(content, "title", 120), 1104, 32, 32, 2, true);
        int startTop = 40 + title.height() + 16;
        int y = startTop + 40 + 20;
        StringBuilder svg = new StringBuilder();
        text(svg, title, 48, 40, INK, true);
        terminal(svg, startTop, thai ? "เริ่มต้น" : "Start");
        arrow(svg, 600, startTop + 40, y);
        for (JsonNode step : steps) {
            String type = step.path("type").asText("process");
            if ("decision".equals(type)) {
                if (!step.path("body").asText("").isBlank()) {
                    throw new ImageGenerationException("decision body must be empty; put its question in heading");
                }
                Block question = fit(label(step, "heading", 80), 260, 24, 22, 5, true);
                Block yes = fit(label(step, "yes", 100), 352, 22, 20, 4, false);
                Block no = fit(label(step, "no", 100), 352, 22, 20, 4, false);
                int diamondHeight = Math.max(120, 2 * (question.height() + 24));
                int branchTop = y + diamondHeight + 24;
                int branchHeight = Math.max(72, Math.max(yes.height(), no.height()) + 32);
                int mergeY = branchTop + branchHeight + 24;
                polygon(svg, "600," + y + " 860," + (y + diamondHeight / 2) + " 600," + (y + diamondHeight) + " 340," + (y + diamondHeight / 2),
                        "#FFF5DF", "#8A671F");
                text(svg, question, 600, y + (diamondHeight - question.height()) / 2, INK, true, true);
                line(svg, 340, y + diamondHeight / 2, 280, y + diamondHeight / 2);
                line(svg, 860, y + diamondHeight / 2, 920, y + diamondHeight / 2);
                text(svg, fit(thai ? "ใช่" : "Yes", 96, 20, 20, 1, false), 252, y + diamondHeight / 2 - 30, BODY, false, true);
                text(svg, fit(thai ? "ไม่ใช่" : "No", 96, 20, 20, 1, false), 948, y + diamondHeight / 2 - 30, BODY, false, true);
                arrow(svg, 280, y + diamondHeight / 2, branchTop);
                arrow(svg, 920, y + diamondHeight / 2, branchTop);
                rect(svg, 80, branchTop, 400, branchHeight, 3, "#FFFFFF", BLUE);
                rect(svg, 720, branchTop, 400, branchHeight, 3, "#FFFFFF", BLUE);
                text(svg, yes, 280, branchTop + (branchHeight - yes.height()) / 2, INK, false, true);
                text(svg, no, 920, branchTop + (branchHeight - no.height()) / 2, INK, false, true);
                line(svg, 280, branchTop + branchHeight, 280, mergeY);
                line(svg, 920, branchTop + branchHeight, 920, mergeY);
                line(svg, 280, mergeY, 920, mergeY);
                y = mergeY;
            } else {
                Block heading = fit(label(step, "heading", 80), 592, 24, 22, 3, true);
                String bodyLabel = step.path("body").asText("");
                Block body = bodyLabel.isBlank() ? new Block(List.of(), 20, 30, 0)
                        : fit(label(step, "body", 180), 592, 22, 20, 4, false);
                int textHeight = heading.height() + (body.height() == 0 ? 0 : 4 + body.height());
                int nodeHeight = Math.max(72, textHeight + 32);
                int nodeWidth = Math.max(280, (int) Math.ceil(Math.max(measuredWidth(heading, true), measuredWidth(body, false)) / 0.9) + 112);
                int left = (1200 - nodeWidth) / 2, right = left + nodeWidth;
                if ("process".equals(type)) rect(svg, left, y, nodeWidth, nodeHeight, 3, "#FFFFFF", BLUE);
                else polygon(svg, (left + 24) + "," + y + " " + right + "," + y + " " + (right - 24) + "," + (y + nodeHeight) + " " + left + "," + (y + nodeHeight),
                        "#EDF5FA", BLUE);
                int textTop = y + (nodeHeight - textHeight) / 2;
                text(svg, heading, 600, textTop, INK, true, true);
                text(svg, body, 600, textTop + heading.height() + 4, BODY, false, true);
                y += nodeHeight;
            }
            arrow(svg, 600, y, y + 20);
            y += 20;
        }
        terminal(svg, y, thai ? "จบ" : "End");
        return canvas(svg, y + 40 + 40);
    }

    private static String canvas(StringBuilder drawing, int height) {
        height = Math.max(SafeSvg.MIN_HEIGHT, height);
        if (height > SafeSvg.MAX_HEIGHT) throw new ImageGenerationException("graphic is too tall; split or shorten its content");
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"")
                .append(height).append("\" viewBox=\"0 0 1200 ").append(height).append("\">");
        rect(svg, 0, 0, 1200, height, 0, "#F4F8FB", null);
        return svg.append(drawing).append("</svg>").toString();
    }

    private static void terminal(StringBuilder svg, int y, String label) {
        rect(svg, 500, y, 200, 40, 20, "#E6F3F4", TEAL);
        Block text = fit(label, 160, 22, 22, 1, true);
        text(svg, text, 600, y + (40 - text.height()) / 2, INK, true, true);
    }

    private static void polygon(StringBuilder svg, String points, String fill, String stroke) {
        svg.append("<polygon points=\"").append(points).append("\" fill=\"")
                .append(fill).append("\" stroke=\"").append(stroke).append("\"/>");
    }

    private static void line(StringBuilder svg, int x1, int y1, int x2, int y2) {
        svg.append("<line x1=\"").append(x1).append("\" y1=\"").append(y1)
                .append("\" x2=\"").append(x2).append("\" y2=\"").append(y2)
                .append("\" stroke=\"").append(TEAL).append("\" stroke-width=\"2\"/>");
    }

    private static void arrow(StringBuilder svg, int x, int y1, int y2) {
        line(svg, x, y1, x, y2 - 8);
        polygon(svg, (x - 6) + "," + (y2 - 9) + " " + (x + 6) + "," + (y2 - 9) + " " + x + "," + (y2 - 1), TEAL, TEAL);
    }

    private static String label(JsonNode node, String key, int maximum) {
        JsonNode value = node.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maximum) {
            throw new ImageGenerationException("infographic field is invalid or too long: " + key);
        }
        return value.asText().strip();
    }

    private static Block fit(String text, int width, int size, int minimum, int maxLines, boolean bold) {
        for (int candidate = size; candidate >= minimum; candidate--) {
            List<String> lines = wrap(text, width, candidate, bold);
            if (lines.size() <= maxLines) {
                int lineHeight = (int) Math.ceil(candidate * 1.5);
                return new Block(lines, candidate, lineHeight, lines.size() * lineHeight);
            }
        }
        throw new ImageGenerationException("infographic text is too long; shorten this field: " + text);
    }

    private static int measuredWidth(Block block, boolean bold) {
        Font font = new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, block.size());
        return (int) Math.ceil(block.lines().stream().mapToDouble(line ->
                new TextLayout(line, font, METRICS).getAdvance()).max().orElse(0));
    }

    static List<String> wrap(String text, double width, int size, boolean bold) {
        if (width < size * 2) throw new ImageGenerationException("graphic text box is too narrow");
        Font font = new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size);
        List<String> lines = new ArrayList<>();
        // ponytail: JVM/browser fallback fonts can differ; reserve 10%, bundle a shared font if exact metrics are needed.
        float measure = (float) (width * 0.9);
        for (String paragraph : text.split("\\R")) {
            if (paragraph.isBlank()) continue;
            AttributedString attributed = new AttributedString(paragraph);
            attributed.addAttribute(TextAttribute.FONT, font);
            LineBreakMeasurer breaks = new LineBreakMeasurer(attributed.getIterator(),
                    BreakIterator.getLineInstance(THAI), METRICS);
            while (breaks.getPosition() < paragraph.length()) {
                int start = breaks.getPosition();
                TextLayout line = breaks.nextLayout(measure);
                if (line == null || line.getVisibleAdvance() > measure + 1) {
                    throw new ImageGenerationException("graphic text cannot fit its box");
                }
                String value = paragraph.substring(start, breaks.getPosition()).strip();
                if (!value.isEmpty()) lines.add(value);
            }
        }
        return lines;
    }

    private static void text(StringBuilder svg, Block block, int x, int top, String color, boolean bold) {
        text(svg, block, x, top, color, bold, false);
    }

    private static void text(StringBuilder svg, Block block, int x, int top, String color, boolean bold, boolean centered) {
        for (int index = 0; index < block.lines().size(); index++) {
            svg.append("<text x=\"").append(x).append("\" y=\"")
                    .append(top + block.size() + index * block.lineHeight())
                    .append("\" text-anchor=\"").append(centered ? "middle" : "start")
                    .append("\" font-family=\"sans-serif\" font-size=\"").append(block.size())
                    .append("\" font-weight=\"").append(bold ? "bold" : "normal")
                    .append("\" fill=\"").append(color).append("\">")
                    .append(escape(block.lines().get(index))).append("</text>");
        }
    }

    private static void rect(StringBuilder svg, int x, int y, int width, int height, int radius, String fill, String stroke) {
        svg.append("<rect x=\"").append(x).append("\" y=\"").append(y).append("\" width=\"")
                .append(width).append("\" height=\"").append(height).append("\" rx=\"")
                .append(radius).append("\" fill=\"").append(fill).append('"');
        if (stroke != null) svg.append(" stroke=\"").append(stroke).append('"');
        svg.append("/>");
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private record Block(List<String> lines, int size, int lineHeight, int height) { }
}
