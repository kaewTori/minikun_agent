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
            assertEquals(TextAutofit.NORMAL, subtitle.getTextAutofit());
            assertTrue(slideXml(bytes, "ppt/slides/slide1.xml").contains("<a:cs typeface=\"Sarabun\""));
            var comparisonBullets = deck.getSlides().get(1).getShapes().stream()
                    .filter(XSLFTextShape.class::isInstance).map(XSLFTextShape.class::cast)
                    .filter(shape -> shape.getText().contains("ค้นข้อมูล") || shape.getText().contains("สร้างสไลด์"))
                    .toList();
            assertEquals(2, comparisonBullets.size());
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
