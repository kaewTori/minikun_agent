package com.minikun.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.sl.usermodel.TextShape.TextAutofit;
import org.apache.poi.sl.usermodel.VerticalAlignment;
import org.junit.jupiter.api.Test;

class PresentationRendererTest {
    @Test
    void createsEditableThaiSlidesAndSpeakerNotes() throws Exception {
        var cover = slide("มินิคุง", "cover", "ผู้ช่วยของเรา");
        var content = new PresentationSpec.SlideSpec("ระบบใน homelab", "comparison", "", List.of(),
                "บริการ", List.of("ค้นข้อมูล", "สร้างสไลด์"), "ส่วนที่ดูแลเอง", List.of("สำรองข้อมูล"),
                "", "", "", "", List.of(), "", "หลักฐานจากเอกสารที่แนบ", List.of("https://example.test/source"));
        var cards = new PresentationSpec.SlideSpec("จุดเด่น", "cards", "สิ่งที่ทำได้", List.of("ค้นข้อมูล", "สร้างสไลด์"),
                "", List.of(), "", List.of(), "", "", "", "", List.of(), "", "", List.of());
        byte[] bytes = new PresentationRenderer(null).render(
                new PresentationSpec("มินิคุง", "th", "japanese", List.of(cover, content, cards))).bytes();

        try (XMLSlideShow deck = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            assertEquals(3, deck.getSlides().size());
            var title = deck.getSlides().get(0).getShapes().stream().filter(XSLFTextShape.class::isInstance)
                    .map(XSLFTextShape.class::cast).filter(shape -> shape.getText().contains("มินิคุง"))
                    .findFirst().orElseThrow();
            assertTrue(title.getText().contains("มินิคุง"));
            var subtitle = deck.getSlides().get(0).getShapes().stream().filter(XSLFTextShape.class::isInstance)
                    .map(XSLFTextShape.class::cast).filter(shape -> shape.getText().contains("ผู้ช่วยของเรา"))
                    .findFirst().orElseThrow();
            assertEquals(VerticalAlignment.TOP, subtitle.getVerticalAlignment());
            assertEquals(TextAutofit.NONE, subtitle.getTextAutofit());
            assertEquals(24.0, subtitle.getTextParagraphs().getFirst().getTextRuns().getFirst().getFontSize());
            assertEquals(1, subtitle.getTextParagraphs().size());
            assertTrue(slideXml(bytes, "ppt/slides/slide1.xml").contains("<a:cs typeface=\"Sarabun\""));
            var comparisonBullets = deck.getSlides().get(1).getShapes().stream()
                    .filter(XSLFTextShape.class::isInstance).map(XSLFTextShape.class::cast)
                    .filter(shape -> shape.getText().contains("ค้นข้อมูล") || shape.getText().contains("สร้างสไลด์"))
                    .toList();
            assertEquals(2, comparisonBullets.size());
            assertTrue(comparisonBullets.stream().allMatch(shape -> shape.getTextParagraphs().getFirst()
                    .getTextRuns().getFirst().getFontSize() >= 24));
            assertTrue(comparisonBullets.get(1).getAnchor().getY() - comparisonBullets.get(0).getAnchor().getY() < 80);
            assertTrue(deck.getSlides().get(2).getShapes().stream().anyMatch(XSLFAutoShape.class::isInstance));
            assertTrue(deck.getSlides().get(2).getShapes().stream().filter(XSLFTextShape.class::isInstance)
                    .map(XSLFTextShape.class::cast).anyMatch(shape -> shape.getText().contains("สร้างสไลด์")));
            String notes = deck.getNotesSlide(deck.getSlides().get(1)).getTextParagraphs().stream()
                    .flatMap(List::stream).map(paragraph -> paragraph.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(notes.contains("https://example.test/source"));
        }
    }

    @Test
    void separatesThaiExplanationsAndCodeWithComfortableLineAndParagraphSpacing() throws Exception {
        String body = "ส่งไฟล์ที่ต้องการแก้ให้ Copilot เพื่อให้รู้บริบท\n\n"
                + "ตรวจข้อเสนอก่อนนำไปใช้ และรันเทสต์เพื่อยืนยันผล\n\n"
                + "public double discountAmount(double total) {\n"
                + "  return total > 1000 ? total * 0.10 : 0.0;\n}";
        var spec = new PresentationSpec("ตัวอย่าง", "th", "paper", List.of(slide("ตรวจโค้ดก่อนใช้", "editorial", body)));
        try (var deck = new XMLSlideShow(new ByteArrayInputStream(new PresentationRenderer(null).render(spec).bytes()))) {
            var text = deck.getSlides().getFirst().getShapes().stream().filter(XSLFTextShape.class::isInstance)
                    .map(XSLFTextShape.class::cast).filter(shape -> shape.getText().contains("discountAmount"))
                    .findFirst().orElseThrow();
            assertEquals(body.replace("\n\n", "\n"), text.getText());
            assertEquals(3, text.getTextParagraphs().size());
            assertTrue(text.getTextParagraphs().getLast().getText().contains("\n  return"));
            for (var paragraph : text.getTextParagraphs()) {
                assertEquals(-38.4, paragraph.getLineSpacing(), 0.01);
                assertEquals(8.0, paragraph.getSpaceAfter());
            }
            assertTrue(text.getTextHeight() <= text.getAnchor().getHeight() + 2);
        }
    }

    @Test
    void rejectsTextThatWouldNeedShrinkingInsteadOfDeliveringUnreadableSlides() {
        var content = slide("ตัวอย่าง", "editorial", "รายละเอียดที่ยาวเกินพื้นที่ของสไลด์ ".repeat(50));
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PresentationRenderer(null).render(
                        new PresentationSpec("ตัวอย่าง", "th", "paper", List.of(content))));
        assertTrue(error.getMessage().contains("does not fit at 24pt"));
    }

    @Test
    void everyLayoutKeepsVisibleContentAt24PointsWithoutAutofit() throws Exception {
        var spec = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {"title":"ตัวอย่าง","theme":"paper","slides":[
                  {"title":"การใช้ Copilot","layout":"cover","body":"ตัวอย่างสำหรับนักพัฒนา"},
                  {"title":"บริบทที่ใช้","layout":"editorial","bullets":["แนบไฟล์ที่เกี่ยวข้อง","ระบุผลที่คาดหวัง","ตรวจผลด้วยเทสต์"]},
                  {"title":"ร่างโค้ด","layout":"split","body":"อธิบายเงื่อนไขก่อนให้ AI ร่างโค้ด","bullets":["ข้อมูลเข้า","ผลลัพธ์","กรณีผิดพลาด"]},
                  {"title":"งานที่ช่วยได้","layout":"cards","bullets":["อธิบายโค้ด","ร่างฟังก์ชัน","เสนอ refactor","เพิ่มเทสต์","สรุป diff"]},
                  {"title":"ตรวจผลลัพธ์","layout":"comparison","leftTitle":"กรณีปกติ","leftBullets":["ราคา 100","ส่วนลด 10%","เหลือ 90"],"rightTitle":"กรณีขอบเขต","rightBullets":["ราคา 0","ส่วนลด 100%","เหลือ 0"]},
                  {"title":"ผลทดสอบตัวอย่าง","layout":"stat","value":"90","valueLabel":"ราคาเมื่อหักส่วนลด","body":"ราคา 100 หักส่วนลด 10% เป็นตัวอย่างประกอบ"},
                  {"title":"หลักการตรวจโค้ด","layout":"quote","quote":"ตรวจเงื่อนไขและรันเทสต์ก่อนใช้โค้ด","attribution":"แนวทางสำหรับตัวอย่างนี้"},
                  {"title":"ขั้นตอนการทำงาน","layout":"timeline","timeline":[
                    {"label":"บริบท","text":"แนบไฟล์และระบุโจทย์"},{"label":"ร่าง","text":"ขอโค้ดพร้อมคำอธิบาย"},
                    {"label":"ตรวจ","text":"อ่าน diff และตรวจเงื่อนไข"},{"label":"ทดสอบ","text":"รันกรณีปกติและขอบเขต"},
                    {"label":"ปรับ","text":"แก้ข้อผิดพลาดก่อนใช้งาน"}]}]}
                """, PresentationSpec.class);
        try (var deck = new XMLSlideShow(new ByteArrayInputStream(new PresentationRenderer(null).render(spec).bytes()))) {
            assertEquals(8, deck.getSlides().size());
            for (var slide : deck.getSlides()) {
                for (var shape : slide.getShapes()) {
                    if (!(shape instanceof XSLFTextShape text) || text.getText().isBlank()
                            || text.getText().matches("\\d{2} / \\d{2}")) continue;
                    assertEquals(TextAutofit.NONE, text.getTextAutofit());
                    assertTrue(text.getTextHeight() <= text.getAnchor().getHeight() + 2);
                    for (var paragraph : text.getTextParagraphs()) {
                        for (var run : paragraph.getTextRuns()) assertTrue(run.getFontSize() >= 24);
                    }
                }
            }
        }
    }

    @Test
    void realisticThaiCopyUsesWiderLayoutsWithoutShrinking() throws Exception {
        var spec = new com.fasterxml.jackson.databind.ObjectMapper().readValue("""
                {
                  "title": "Copilot",
                  "theme": "paper",
                  "slides": [
                    {
                      "layout": "editorial",
                      "title": "Copilot ทำงานอย่างไร? (Core Features)",
                      "bullets": [
                        "เริ่มพิมพ์เมธอด แล้วตรวจโค้ดที่ Copilot เสนอก่อนรับไปใช้",
                        "เลือกโค้ดที่ไม่เข้าใจ แล้วถาม Chat ให้อธิบายการทำงาน",
                        "แนบไฟล์ที่เกี่ยวข้อง เพื่อให้คำตอบใช้บริบทของงานจริง"
                      ]
                    },
                    {
                      "layout": "comparison",
                      "title": "เหมาะกับใคร? (Who is it for?)",
                      "leftBullets": [
                        "ผู้เริ่มต้น: ขอคำอธิบายโค้ดทีละขั้น เพื่อเรียนรู้โครงสร้างภาษา",
                        "ลองแก้ตัวอย่างแล้วรันดูผล"
                      ],
                      "rightBullets": [
                        "ผู้มีประสบการณ์: ขอแยกเงื่อนไขโค้ดเก่า โดยคงผลลัพธ์เดิม",
                        "รันเทสต์ก่อนและหลังแก้โค้ด"
                      ]
                    },
                    {
                      "layout": "cover",
                      "title": "สรุป: ใช้ให้เป็นเครื่องมือเสริมพลัง",
                      "body": "GitHub Copilot คือผู้ช่วยที่ยอดเยี่ยม แต่ไม่ใช่ตัวแทนทั้งหมดในการเขียนโค้ด การใช้ให้ถูกวิธีจะช่วยให้พี่สาวทำงานได้เร็วขึ้นและมีคุณภาพมากขึ้นแน่นอนครับ!"
                    }
                  ]
                }
                """, PresentationSpec.class);
        try (var deck = new XMLSlideShow(new ByteArrayInputStream(new PresentationRenderer(null).render(spec).bytes()))) {
            assertEquals(3, deck.getSlides().size());
            for (var slide : deck.getSlides()) {
                String visible = slide.getShapes().stream().filter(XSLFTextShape.class::isInstance)
                        .map(XSLFTextShape.class::cast).map(XSLFTextShape::getText)
                        .collect(java.util.stream.Collectors.joining("\n"));
                for (var shape : slide.getShapes()) {
                    if (!(shape instanceof XSLFTextShape text) || text.getText().isBlank()
                            || text.getText().matches("\\d{2} / \\d{2}")) continue;
                    assertEquals(TextAutofit.NONE, text.getTextAutofit());
                    assertTrue(text.getTextHeight() <= text.getAnchor().getHeight() + 2);
                    for (var paragraph : text.getTextParagraphs()) {
                        for (var run : paragraph.getTextRuns()) assertTrue(run.getFontSize() >= 24);
                    }
                }
                var expected = spec.slides().get(slide.getSlideNumber() - 1);
                for (String point : expected.bullets()) assertTrue(visible.contains(point));
                for (String point : expected.leftBullets()) assertTrue(visible.contains(point));
                for (String point : expected.rightBullets()) assertTrue(visible.contains(point));
                assertTrue(visible.contains(expected.body()));
            }
        }
    }

    private PresentationSpec.SlideSpec slide(String title, String layout, String body) {
        return new PresentationSpec.SlideSpec(title, layout, body, List.of(), "", List.of(), "", List.of(),
                "", "", "", "", List.of(), "", "", List.of());
    }

    private String slideXml(byte[] pptx, String name) throws IOException {
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(pptx))) {
            for (ZipEntry entry; (entry = archive.getNextEntry()) != null;) {
                if (entry.getName().equals(name)) return new String(archive.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return "";
    }
}
