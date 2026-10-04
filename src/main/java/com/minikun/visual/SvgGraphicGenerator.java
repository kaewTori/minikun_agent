package com.minikun.visual;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.model.task.TaskModelMessage;
import com.minikun.model.task.TaskModelProvider;
import com.minikun.model.task.TaskModelRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Gemma supplies content or primitives; the application renders safe SVG. */
public final class SvgGraphicGenerator {
    // ponytail: fixed width and five primitives; use a graph layout engine for complex connected diagrams.
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    private static final Pattern POINTS = Pattern.compile("[0-9+.,\\-\\s]{3,500}");
    private static final Pattern INFOGRAPHIC = Pattern.compile("อินโฟกราฟิก|infographic|(?:รูปแบบ|แบบ)\\s*info\\b|การ์ดข้อความ|text card", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOWCHART = Pattern.compile("flowchart|ผังงาน", Pattern.CASE_INSENSITIVE);
    private static final Pattern TARGET_FORMAT = Pattern.compile(
            "(?:เป็น|รูปแบบ|แบบ|\\bto\\b|\\bas\\b)\\s*(?:อินโฟกราฟิก|infographic|info\\b|flowchart|ผังงาน|"
                    + "แผนผัง|แผนภาพ|diagram|timeline|ไทม์ไลน์|chart|กราฟ(?!ิก)|mind ?map|การ์ดข้อความ|text card)",
            Pattern.CASE_INSENSITIVE);
    private static final String POLICY = """
            Design one polished diagram from the user's request, sizing its content for readability.
            The canvas is 1200px wide. Choose height from 480 to 3200px according to the content.
            Return exactly one JSON object: {"height":800,"background":"#ffffff","elements":[...]}.
            Allowed element objects:
            {"type":"rect","x":0,"y":0,"width":100,"height":50,"rx":12,"fill":"#ffffff"}
            {"type":"circle","cx":100,"cy":100,"r":24,"fill":"#2563eb"}
            {"type":"line","x1":0,"y1":0,"x2":100,"y2":100,"stroke":"#2563eb","strokeWidth":3}
            {"type":"polygon","points":"0,0 20,10 0,20","fill":"#2563eb"}
            {"type":"text","x":60,"y":90,"text":"Title","fontSize":42,"fill":"#222222","fontWeight":"bold","anchor":"start"}
            Use only these types and fields. Colors must be six-digit hex. Use absolute coordinates,
            at most 80 elements, and keep every shape and label inside the canvas. Split long labels
            into separate text elements. Keep body text at least 24px. Use the user's language.
            Keep labels inside their enclosing rectangles with at least 20px padding.
            Adapt font sizes to the visual hierarchy, wrap labels and enlarge their boxes before
            reducing body text size. Arrange and space the content, then choose a matching canvas height.
            Preserve requested details instead of clipping or abbreviating them just to fit a small box.
            Text may specify maxWidth in pixels to limit its wrapping area.
            For three equal cards, use x=50, 435, 820 and width=330; center their labels at
            x=215, 600, 985. Never let x+width exceed 1200 or y+height exceed the chosen canvas height.
            Use only numerical facts supplied by the user. If chart values are missing, design a
            conceptual diagram without invented statistics. Do not add numerical digits absent from the request,
            including step counts. Use the CURRENT request's format; previous graphic context supplies content only.
            For flowcharts, connect the process nodes using lines and triangular arrowheads. Keep nodes and
            labels distinct, preserve the previous topic and process steps, and do not return infographic cards.
            Never include SVG, HTML, markdown, or code.
            """;

    private final TaskModelProvider model;
    private final GeneratedImageStore store;
    private final ObjectMapper json;

    public SvgGraphicGenerator(TaskModelProvider model, GeneratedImageStore store, ObjectMapper json) {
        this.model = Objects.requireNonNull(model);
        this.store = Objects.requireNonNull(store);
        this.json = Objects.requireNonNull(json);
    }

    public GeneratedImageStore.StoredImage generate(String request) {
        String brief = Objects.requireNonNullElse(request, "").strip();
        if (brief.isBlank()) throw new ImageGenerationException("graphic request is blank");
        if (brief.length() > 8_000) brief = brief.substring(0, 8_000);
        String format = StoryIllustrationIntentDetector.currentRequest(brief);
        var targetFormat = TARGET_FORMAT.matcher(format);
        while (targetFormat.find()) format = targetFormat.group();
        boolean infographic = INFOGRAPHIC.matcher(format).find();
        boolean flowchart = !infographic && FLOWCHART.matcher(format).find();
        String policy = flowchart ? InfographicRenderer.FLOWCHART_POLICY
                : infographic ? InfographicRenderer.POLICY : POLICY;
        String response = draw(policy, brief);
        try {
            return store(brief, response, infographic, flowchart);
        } catch (ImageGenerationException invalid) {
            String repair = policy + "\nPrevious JSON was rejected: " + invalid.getMessage()
                    + ". Return corrected JSON matching the schema. Shorten text if it does not fit.";
            String previous = response == null ? "" : response.substring(0, Math.min(response.length(), 12_000));
            return store(brief, draw(repair, "Request: " + brief + "\nRejected JSON:\n" + previous), infographic, flowchart);
        }
    }

    private String draw(String policy, String brief) {
        return model.generate(new TaskModelRequest(
                List.of(new TaskModelMessage("system", policy), new TaskModelMessage("user", brief)),
                5_000, 0.2, TaskModelRequest.ResponseFormat.JSON_OBJECT));
    }

    private GeneratedImageStore.StoredImage store(String brief, String response, boolean infographic, boolean flowchart) {
        try {
            JsonNode root = json.readTree(response);
            if (infographic || flowchart) {
                if (root == null) throw new ImageGenerationException("graphic content is missing");
                byte[] checked = SafeSvg.sanitize(flowchart ? InfographicRenderer.renderFlowchart(root)
                        : InfographicRenderer.render(root), brief);
                return store.saveSvg(new String(checked, StandardCharsets.UTF_8));
            }
            if (root == null || !root.isObject() || !root.path("elements").isArray()
                    || root.path("elements").isEmpty() || root.path("elements").size() > 80) {
                throw new ImageGenerationException("graphic requires 1 to 80 elements");
            }
            int height = root.has("height") ? number(root, "height", SafeSvg.MIN_HEIGHT, SafeSvg.MAX_HEIGHT) : 800;
            StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"")
                    .append(height).append("\" viewBox=\"0 0 1200 ").append(height).append("\">");
            svg.append("<rect x=\"0\" y=\"0\" width=\"1200\" height=\"").append(height).append("\" fill=\"")
                    .append(color(root, "background", "#ffffff")).append("\"/>");
            for (JsonNode element : root.path("elements")) append(svg, element, root.path("elements"), height);
            svg.append("</svg>");
            byte[] checked = SafeSvg.sanitize(svg.toString(), brief);
            return store.saveSvg(new String(checked, StandardCharsets.UTF_8));
        } catch (ImageGenerationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ImageGenerationException("graphic JSON is invalid", exception);
        }
    }

    private void append(StringBuilder svg, JsonNode element, JsonNode elements, int canvasHeight) {
        if (!element.isObject()) throw new ImageGenerationException("graphic element is invalid");
        switch (element.path("type").asText()) {
            case "rect" -> {
                int x = number(element, "x", 0, 1200);
                int y = number(element, "y", 0, canvasHeight);
                int width = number(element, "width", 1, 1200);
                int height = number(element, "height", 1, canvasHeight);
                if (x + width > 1200 || y + height > canvasHeight) {
                    throw new ImageGenerationException("rectangle extends beyond the canvas");
                }
                svg.append("<rect");
                n(svg, element, "x", 0, 1200); n(svg, element, "y", 0, canvasHeight);
                n(svg, element, "width", 1, 1200); n(svg, element, "height", 1, canvasHeight);
                if (element.has("rx")) n(svg, element, "rx", 0, 100);
                c(svg, element, "fill", "#ffffff"); svg.append("/>");
            }
            case "circle" -> {
                int cx = number(element, "cx", 0, 1200);
                int cy = number(element, "cy", 0, canvasHeight);
                int radius = number(element, "r", 1, 600);
                if (cx - radius < 0 || cx + radius > 1200 || cy - radius < 0 || cy + radius > canvasHeight) {
                    throw new ImageGenerationException("circle extends beyond the canvas");
                }
                svg.append("<circle");
                n(svg, element, "cx", 0, 1200); n(svg, element, "cy", 0, canvasHeight);
                n(svg, element, "r", 1, 600); c(svg, element, "fill", "#2563eb"); svg.append("/>");
            }
            case "line" -> {
                svg.append("<line");
                n(svg, element, "x1", 0, 1200); n(svg, element, "y1", 0, canvasHeight);
                n(svg, element, "x2", 0, 1200); n(svg, element, "y2", 0, canvasHeight);
                svg.append(" stroke=\"").append(color(element, "stroke", "#2563eb")).append('"');
                svg.append(" stroke-width=\"").append(element.has("strokeWidth")
                        ? number(element, "strokeWidth", 1, 20) : 3).append("\"/>");
            }
            case "polygon" -> {
                String points = element.path("points").asText("");
                if (!POINTS.matcher(points).matches()) throw new ImageGenerationException("polygon points are invalid");
                String[] coordinates = points.trim().split("[\\s,]+");
                if (coordinates.length < 6 || coordinates.length % 2 != 0) {
                    throw new ImageGenerationException("polygon points are invalid");
                }
                for (int index = 0; index < coordinates.length; index += 2) {
                    double x = Double.parseDouble(coordinates[index]);
                    double y = Double.parseDouble(coordinates[index + 1]);
                    if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1200 || y < 0 || y > canvasHeight) {
                        throw new ImageGenerationException("polygon extends beyond the canvas");
                    }
                }
                svg.append("<polygon points=\"").append(points).append('"');
                c(svg, element, "fill", "#2563eb"); svg.append("/>");
            }
            case "text" -> {
                JsonNode label = element.path("text");
                if (!label.isTextual() || label.asText().isBlank() || label.asText().length() > 200) {
                    throw new ImageGenerationException("graphic label is invalid");
                }
                String weight = element.path("fontWeight").asText("normal");
                String anchor = element.path("anchor").asText("start");
                if ("center".equals(anchor)) anchor = "middle";
                if ("left".equals(anchor)) anchor = "start";
                if ("right".equals(anchor)) anchor = "end";
                if (!weight.matches("normal|bold") || !anchor.matches("start|middle|end")) {
                    throw new ImageGenerationException("graphic text style is invalid");
                }
                int x = number(element, "x", 0, 1200);
                int fontSize = number(element, "fontSize", 14, 100);
                int y = number(element, "y", 0, canvasHeight);
                double available = switch (anchor) {
                    case "middle" -> 2.0 * Math.min(x, 1200 - x);
                    case "end" -> x;
                    default -> 1200 - x;
                } - 20;
                double bottom = canvasHeight;
                double enclosingArea = Double.POSITIVE_INFINITY;
                for (JsonNode shape : elements) {
                    if (!"rect".equals(shape.path("type").asText())) continue;
                    double left = number(shape, "x", 0, 1200), top = number(shape, "y", 0, canvasHeight);
                    double width = number(shape, "width", 1, 1200), height = number(shape, "height", 1, canvasHeight);
                    if (x >= left && x < left + width && y >= top + fontSize && y < top + height
                            && width * height < enclosingArea) {
                        enclosingArea = width * height;
                        available = switch (anchor) {
                            case "middle" -> 2 * Math.min(x - left, left + width - x) - 40;
                            case "end" -> x - left - 20;
                            default -> left + width - x - 20;
                        };
                        bottom = top + height - 20;
                    }
                }
                if (element.has("maxWidth")) available = Math.min(available, number(element, "maxWidth", 1, 1200));
                List<String> lines = InfographicRenderer.wrap(label.asText(), available, fontSize, "bold".equals(weight));
                int lineHeight = (int) Math.ceil(fontSize * 1.5);
                if (lines.size() > 6 || y - fontSize < 0
                        || y + (lines.size() - 1) * lineHeight + fontSize * 0.3 > bottom) {
                    throw new ImageGenerationException("graphic label extends beyond its text box; shorten the label");
                }
                for (int index = 0; index < lines.size(); index++) {
                    svg.append("<text x=\"").append(x).append("\" y=\"")
                            .append(y + index * lineHeight).append("\" font-size=\"")
                            .append(fontSize).append("\" font-family=\"sans-serif\" font-weight=\"")
                            .append(weight).append("\" text-anchor=\"").append(anchor).append('"');
                    c(svg, element, "fill", "#222222");
                    svg.append('>').append(escape(lines.get(index))).append("</text>");
                }
            }
            default -> throw new ImageGenerationException("graphic element type is unsupported");
        }
    }

    private void n(StringBuilder svg, JsonNode node, String key, int minimum, int maximum) {
        svg.append(' ').append(key).append("=\"")
                .append(number(node, key, minimum, maximum)).append('"');
    }

    private int number(JsonNode node, String key, int minimum, int maximum) {
        JsonNode value = node.path(key);
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())
                || value.doubleValue() < minimum || value.doubleValue() > maximum) {
            throw new ImageGenerationException("graphic coordinate is invalid: " + key);
        }
        return (int) Math.round(value.doubleValue());
    }

    private void c(StringBuilder svg, JsonNode node, String key, String fallback) {
        svg.append(' ').append(key).append("=\"").append(color(node, key, fallback)).append('"');
    }

    private String color(JsonNode node, String key, String fallback) {
        String value = node.path(key).asText(fallback);
        if (!COLOR.matcher(value).matches()) throw new ImageGenerationException("graphic color is invalid");
        return value;
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

}
