package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InfographicRendererTest {
    @TempDir Path directory;
    private static final String CONTENT = """
            {"title":"ทำความรู้จัก PostgreSQL",
             "subtitle":"ฐานข้อมูลเชิงสัมพันธ์ (Relational Database)",
             "cards":[
               {"heading":"ความเสถียรสูง","body":"ออกแบบมาเพื่อความน่าเชื่อถือ ช่วยดูแลข้อมูลสำคัญของแอปพลิเคชัน"},
               {"heading":"คุณสมบัติขั้นสูง","body":"รองรับฟีเจอร์หลากหลาย เช่น การค้นหาข้อมูลที่ซับซ้อนและชนิดข้อมูลที่ยืดหยุ่น"},
               {"heading":"Open Source","body":"ใช้งานได้ฟรีและมีชุมชนขนาดใหญ่ช่วยสนับสนุน"}],
             "summary":"PostgreSQL เป็นระบบจัดการฐานข้อมูลที่ทรงพลังและยืดหยุ่น เหมาะสำหรับแอปพลิเคชันที่ต้องการความน่าเชื่อถือสูง"}
            """;

    @Test
    void laysOutThaiTextInsideCardsWithoutOverlap() throws Exception {
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(SafeSvg.sanitize(InfographicRenderer.render(new ObjectMapper().readTree(CONTENT)))));
        var rectangles = document.getElementsByTagName("rect");
        var labels = document.getElementsByTagName("text");
        int cardLines = 0;
        for (int index = 0; index < labels.getLength(); index++) {
            var label = (org.w3c.dom.Element) labels.item(index);
            double x = Double.parseDouble(label.getAttribute("x")), y = Double.parseDouble(label.getAttribute("y"));
            int size = Integer.parseInt(label.getAttribute("font-size"));
            Font font = new Font(Font.SANS_SERIF, "bold".equals(label.getAttribute("font-weight")) ? Font.BOLD : Font.PLAIN, size);
            TextLayout metrics = new TextLayout(label.getTextContent(), font, new FontRenderContext(null, true, true));
            assertTrue(y - metrics.getAscent() >= 0 && y + metrics.getDescent() <= 800);
            assertTrue(x + metrics.getAdvance() <= 1152);
            for (int rectIndex = 0; rectIndex < rectangles.getLength(); rectIndex++) {
                var rect = (org.w3c.dom.Element) rectangles.item(rectIndex);
                if (!"#FFFFFF".equals(rect.getAttribute("fill"))) continue;
                double left = Double.parseDouble(rect.getAttribute("x")), top = Double.parseDouble(rect.getAttribute("y"));
                double width = Double.parseDouble(rect.getAttribute("width")), height = Double.parseDouble(rect.getAttribute("height"));
                if (x >= left && x < left + width && y >= top && y < top + height) {
                    assertTrue(x >= left + 28 && x + metrics.getAdvance() <= left + width - 28);
                    assertTrue(y - metrics.getAscent() >= top + 20 && y + metrics.getDescent() <= top + height - 20);
                    cardLines++;
                }
            }
            for (int previous = 0; previous < index; previous++) {
                var other = (org.w3c.dom.Element) labels.item(previous);
                if (other.getAttribute("x").equals(label.getAttribute("x"))) {
                    double previousY = Double.parseDouble(other.getAttribute("y"));
                    assertTrue(y - previousY >= size * 1.3, "text lines overlap");
                }
            }
        }
        assertTrue(cardLines > 6, "long Thai bodies must wrap inside their own cards");
    }

    @Test
    void asksForShorterContentRatherThanClippingAndRetainsTheFactGuard() {
        AtomicInteger calls = new AtomicInteger();
        GeneratedImageStore store = new GeneratedImageStore(directory, 128_000, Clock.systemUTC());
        String tooLong = CONTENT.replace("ออกแบบมาเพื่อความน่าเชื่อถือ ช่วยดูแลข้อมูลสำคัญของแอปพลิเคชัน",
                "เนื้อหาที่ต้องอ่านอย่างละเอียด ".repeat(30));
        SvgGraphicGenerator generator = new SvgGraphicGenerator(request -> {
            if (calls.incrementAndGet() == 1) {
                assertTrue(request.messages().getFirst().content().contains("cards"));
                assertFalse(request.messages().getFirst().content().contains("\"x\":"));
                return tooLong;
            }
            assertTrue(request.messages().getFirst().content().contains("Shorten text"));
            return CONTENT;
        }, store, new ObjectMapper());
        var image = generator.generate("ทำอินโฟกราฟิก PostgreSQL");
        assertEquals(2, calls.get());
        assertEquals("image/svg+xml", store.read(image.filename()).contentType());
        org.junit.jupiter.api.Assertions.assertThrows(ImageGenerationException.class, () ->
                new SvgGraphicGenerator(request -> CONTENT.replace("ความเสถียรสูง", "ความเสถียร 99%"),
                        store, new ObjectMapper()).generate("ทำอินโฟกราฟิก PostgreSQL"));
    }

    @Test
    void expandsDenseContentAndPreservesReadableLabelsInOneGeneration() throws Exception {
        var json = new ObjectMapper();
        var infographic = json.readTree(CONTENT).deepCopy();
        String details = "ทำความเข้าใจคำขอ ตรวจสอบข้อเท็จจริง ประเมินข้อจำกัด และแจ้งผลพร้อมความไม่แน่นอน ".repeat(4).strip();
        var cards = ((com.fasterxml.jackson.databind.node.ObjectNode) infographic).putArray("cards");
        for (String heading : java.util.List.of("รับคำขอ", "ตรวจสอบ", "วิเคราะห์", "เปรียบเทียบ", "ตอบสนอง", "ติดตามผล")) {
            cards.addObject().put("heading", heading).put("body", details);
        }
        var flowchart = json.createObjectNode().put("title", "ขั้นตอนการทำงานของมินิคุง");
        var steps = flowchart.putArray("steps");
        String body = "แยกข้อเท็จจริงและประเมินบริบทก่อนตอบสนอง ".repeat(4).strip();
        for (int index = 0; index < 5; index++) {
            steps.addObject().put("heading", "ตรวจสอบและประเมินข้อมูลก่อนเลือกแนวทางที่เหมาะกับคำขอ")
                    .put("body", body);
        }
        for (var entry : java.util.Map.of("infographic", infographic, "flowchart", flowchart).entrySet()) {
            AtomicInteger calls = new AtomicInteger();
            var store = new GeneratedImageStore(directory.resolve(entry.getKey()), 128_000, Clock.systemUTC());
            var generator = new SvgGraphicGenerator(request -> {
                calls.incrementAndGet();
                return entry.getValue().toString();
            }, store, json);
            var image = generator.generate("ทำ " + entry.getKey() + " เกี่ยวกับมินิคุง");
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                    new ByteArrayInputStream(store.read(image.filename()).bytes()));
            int height = Integer.parseInt(document.getDocumentElement().getAttribute("height"));
            assertEquals(1, calls.get(), "valid detailed content must not require shortening by the model");
            assertTrue(height > 800, "dense content must grow beyond the former canvas");
            assertEquals(1200, image.width());
            assertEquals(height, image.height());
            assertEquals("0 0 1200 " + height, document.getDocumentElement().getAttribute("viewBox"));
            String expected = "infographic".equals(entry.getKey()) ? details : body;
            assertTrue(document.getDocumentElement().getTextContent().replaceAll("\\s", "")
                    .contains(expected.replaceAll("\\s", "")), "requested details were lost");
            var labels = document.getElementsByTagName("text");
            for (int index = 0; index < labels.getLength(); index++) {
                var label = (org.w3c.dom.Element) labels.item(index);
                int size = Integer.parseInt(label.getAttribute("font-size"));
                var metrics = new TextLayout(label.getTextContent(), new Font(Font.SANS_SERIF,
                        "bold".equals(label.getAttribute("font-weight")) ? Font.BOLD : Font.PLAIN, size),
                        new FontRenderContext(null, true, true));
                double x = Double.parseDouble(label.getAttribute("x")), y = Double.parseDouble(label.getAttribute("y"));
                double left = "middle".equals(label.getAttribute("text-anchor")) ? x - metrics.getAdvance() / 2 : x;
                assertTrue(left >= 0 && left + metrics.getAdvance() <= 1200);
                assertTrue(y - metrics.getAscent() >= 0 && y + metrics.getDescent() <= height);
                assertTrue(size >= 18, "content must remain readable");
            }
        }
    }

    @Test
    void theLatestRequestedFormatOverridesThePreviousGraphicContext() {
        var store = new GeneratedImageStore(directory, 128_000, Clock.systemUTC());
        var generator = new SvgGraphicGenerator(request -> {
            assertTrue(request.messages().getFirst().content().contains("\"cards\":"));
            assertTrue(request.messages().getLast().content().contains("flowchart"));
            return CONTENT;
        }, store, new ObjectMapper());
        var image = generator.generate("เปลี่ยนเป็น infographic"
                + StoryIllustrationIntentDetector.PREVIOUS_GRAPHIC_CONTEXT + "user: ทำ flowchart PostgreSQL");
        assertEquals("image/svg+xml", store.read(image.filename()).contentType());
    }

    @Test
    void flowchartConversionsPreserveStepsAndFitThaiLabelsBetweenConnectedNodes() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (int count : java.util.List.of(4, 5)) {
            var content = json.createObjectNode().put("title", "ขั้นตอนการทำงานของมินิคุง");
            var steps = content.putArray("steps");
            for (int index = 0; index < count; index++) {
                steps.addObject().put("heading", "ตรวจสอบและประเมินข้อมูล")
                        .put("body", "แยกข้อเท็จจริงและประเมินบริบทก่อนตอบสนอง");
            }
            var store = new GeneratedImageStore(directory.resolve("steps-" + count), 128_000, Clock.systemUTC());
            var generator = new SvgGraphicGenerator(request -> {
                assertTrue(request.messages().getFirst().content().contains("\"steps\":"));
                return content.toString();
            }, store, json);
            var image = generator.generate("เปลี่ยนจาก infographic เป็น flowchart"
                    + StoryIllustrationIntentDetector.PREVIOUS_GRAPHIC_CONTEXT + "user: ทำ infographic มินิคุง");
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                    new ByteArrayInputStream(store.read(image.filename()).bytes()));
            assertEquals(count + 1, document.getElementsByTagName("line").getLength());
            assertEquals(count + 1, document.getElementsByTagName("polygon").getLength());
            var rectangles = document.getElementsByTagName("rect");
            var labels = document.getElementsByTagName("text");
            assertEquals(3 + count * 2, labels.getLength());
            assertTrue(document.getDocumentElement().getTextContent().contains("เริ่มต้น"));
            assertTrue(document.getDocumentElement().getTextContent().contains("จบ"));
            for (int index = 1; index < labels.getLength(); index++) {
                var label = (org.w3c.dom.Element) labels.item(index);
                double x = Double.parseDouble(label.getAttribute("x")), y = Double.parseDouble(label.getAttribute("y"));
                int size = Integer.parseInt(label.getAttribute("font-size"));
                var metrics = new TextLayout(label.getTextContent(), new Font(Font.SANS_SERIF,
                        "bold".equals(label.getAttribute("font-weight")) ? Font.BOLD : Font.PLAIN, size),
                        new FontRenderContext(null, true, true));
                boolean contained = false;
                for (int r = 0; r < rectangles.getLength(); r++) {
                    var rect = (org.w3c.dom.Element) rectangles.item(r);
                    if ("#F4F8FB".equals(rect.getAttribute("fill"))) continue;
                    double top = Double.parseDouble(rect.getAttribute("y"));
                    double height = Double.parseDouble(rect.getAttribute("height"));
                    if (y >= top && y <= top + height) {
                        double left = Double.parseDouble(rect.getAttribute("x"));
                        double width = Double.parseDouble(rect.getAttribute("width"));
                        assertTrue(x - metrics.getAdvance() / 2 >= left + 2);
                        assertTrue(x + metrics.getAdvance() / 2 <= left + width - 2);
                        assertTrue(y - metrics.getAscent() >= top + 2);
                        assertTrue(y + metrics.getDescent() <= top + height - 2);
                        contained = true;
                    }
                }
                assertTrue(contained, "flowchart label escaped its node");
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(ImageGenerationException.class, () ->
                InfographicRenderer.renderFlowchart(json.readTree("{\"title\":\"empty\",\"steps\":[]}")));
    }

    @Test
    void conventionalFlowchartHasTerminalsInputOutputAndRealDecisionBranches() throws Exception {
        String content = """
                {"title":"วิธีทำงานของมินิคุง","steps":[
                  {"type":"input","heading":"รับคำขอ","body":"ระบุเป้าหมาย"},
                  {"type":"decision","heading":"ข้อมูลและหลักฐานเพียงพอสำหรับคำขอนี้หรือไม่?","body":"",
                    "yes":"ประเมินทางเลือกพร้อมหลักฐานและเสนอแนวทางที่เหมาะกับคำขอ",
                    "no":"แจ้งความไม่แน่นอนและขอข้อมูลเพิ่มเติมก่อนสรุปผล"},
                  {"type":"process","heading":"ให้เหตุผล","body":"เลือกแนวทางที่เหมาะสม"},
                  {"type":"output","heading":"ตอบสนอง","body":"แจ้งผลและข้อจำกัด"}]}
                """;
        byte[] svg = SafeSvg.sanitize(InfographicRenderer.renderFlowchart(new ObjectMapper().readTree(content)),
                "ทำ flowchart วิธีทำงานของมินิคุง มีเงื่อนไขเมื่อข้อมูลไม่เพียงพอ");
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(svg));
        var rectangles = document.getElementsByTagName("rect");
        var polygons = document.getElementsByTagName("polygon");
        var shapes = new java.util.ArrayList<java.awt.Shape>();
        int terminals = 0, decisions = 0, inputOutput = 0;
        for (int index = 0; index < rectangles.getLength(); index++) {
            var rect = (org.w3c.dom.Element) rectangles.item(index);
            if ("#F4F8FB".equals(rect.getAttribute("fill"))) continue;
            shapes.add(new java.awt.geom.Rectangle2D.Double(Double.parseDouble(rect.getAttribute("x")),
                    Double.parseDouble(rect.getAttribute("y")), Double.parseDouble(rect.getAttribute("width")),
                    Double.parseDouble(rect.getAttribute("height"))));
            if ("20".equals(rect.getAttribute("rx"))) terminals++;
        }
        for (int index = 0; index < polygons.getLength(); index++) {
            var polygon = (org.w3c.dom.Element) polygons.item(index);
            if ("#FFF5DF".equals(polygon.getAttribute("fill"))) decisions++;
            if ("#EDF5FA".equals(polygon.getAttribute("fill"))) inputOutput++;
            if (TEAL_FILL.equals(polygon.getAttribute("fill"))) continue;
            var shape = new java.awt.Polygon();
            for (String point : polygon.getAttribute("points").strip().split("\\s+")) {
                String[] coordinates = point.split(",");
                shape.addPoint(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
            }
            shapes.add(shape);
        }
        assertEquals(2, terminals);
        assertEquals(1, decisions);
        assertEquals(2, inputOutput);
        var labels = document.getElementsByTagName("text");
        for (int index = 1; index < labels.getLength(); index++) {
            var label = (org.w3c.dom.Element) labels.item(index);
            String value = label.getTextContent();
            if ("ใช่".equals(value) || "ไม่ใช่".equals(value)) continue;
            int size = Integer.parseInt(label.getAttribute("font-size"));
            var metrics = new TextLayout(value, new Font(Font.SANS_SERIF,
                    "bold".equals(label.getAttribute("font-weight")) ? Font.BOLD : Font.PLAIN, size),
                    new FontRenderContext(null, true, true));
            double x = Double.parseDouble(label.getAttribute("x")), y = Double.parseDouble(label.getAttribute("y"));
            var bounds = new java.awt.geom.Rectangle2D.Double(x - metrics.getAdvance() / 2,
                    y - metrics.getAscent(), metrics.getAdvance(), metrics.getAscent() + metrics.getDescent());
            assertTrue(shapes.stream().anyMatch(shape -> shape.contains(bounds)), "text outside flowchart symbol: " + value);
        }
        String text = document.getDocumentElement().getTextContent();
        assertTrue(text.contains("ใช่"));
        assertTrue(text.contains("ไม่ใช่"));
        assertTrue(text.contains("ประเมินทางเลือก"));
        assertTrue(text.contains("แจ้งความไม่แน่นอน"));
        org.junit.jupiter.api.Assertions.assertThrows(ImageGenerationException.class, () ->
                InfographicRenderer.renderFlowchart(new ObjectMapper().readTree(content.replace(
                        "\"no\":\"แจ้งความไม่แน่นอนและขอข้อมูลเพิ่มเติมก่อนสรุปผล\"", "\"no\":\"\""))));
    }

    private static final String TEAL_FILL = "#147D86";

    @Test
    void wrapsPrimitiveDiagramLabelsAtTheEnclosingCardBoundary() throws Exception {
        GeneratedImageStore store = new GeneratedImageStore(directory, 128_000, Clock.systemUTC());
        SvgGraphicGenerator generator = new SvgGraphicGenerator(request -> """
                {"elements":[{"type":"rect","x":50,"y":160,"width":300,"height":250},
                {"type":"text","x":100,"y":230,"fontSize":24,
                "text":"ออกแบบมาเพื่อความน่าเชื่อถือและรองรับข้อมูลสำคัญของแอปพลิเคชัน"}]}
                """, store, new ObjectMapper());
        var image = generator.generate("ทำแผนภาพฐานข้อมูล");
        String svg = new String(store.read(image.filename()).bytes(), StandardCharsets.UTF_8);
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
        var lines = document.getElementsByTagName("text");
        assertTrue(lines.getLength() > 1);
        for (int index = 0; index < lines.getLength(); index++) {
            var line = (org.w3c.dom.Element) lines.item(index);
            TextLayout metrics = new TextLayout(line.getTextContent(), new Font(Font.SANS_SERIF, Font.PLAIN, 24),
                    new FontRenderContext(null, true, true));
            assertTrue(100 + metrics.getAdvance() <= 330, "text escaped its 300px card");
        }
    }
}
