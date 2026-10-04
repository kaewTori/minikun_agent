package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class GeneratedImageStoreTest {
    @TempDir Path directory;

    @Test
    void storesAndReadsAnOpaqueLocalPng() {
        GeneratedImageStore store = new GeneratedImageStore(directory, 1024, Clock.systemUTC());
        byte[] png = png();

        GeneratedImageStore.StoredImage stored = store.save(png);
        GeneratedImageStore.StoredImageContent content = store.read(stored.filename());

        assertEquals("image/png", stored.contentType());
        assertEquals("image/png", content.contentType());
        assertArrayEquals(png, content.bytes());
        assertEquals("/v1/images/generated/" + stored.filename(), stored.url());
    }

    @Test
    void rejectsUnknownFormatsAndTraversalNames() {
        GeneratedImageStore store = new GeneratedImageStore(directory, 1024, Clock.systemUTC());

        assertThrows(ImageGenerationException.class, () -> store.save(new byte[] {1, 2, 3}));
        assertThrows(ImageGenerationException.class, () -> store.read("../secret.png"));
    }

    @Test
    void servesOnlySafeSvgDrawings() {
        GeneratedImageStore store = new GeneratedImageStore(directory, 128_000, Clock.systemUTC());
        String prefix = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"800\" viewBox=\"0 0 1200 800\">";
        var saved = store.saveSvg(prefix + "<rect x=\"0\" y=\"0\" width=\"1200\" height=\"800\" fill=\"white\"/>"
                + "<text x=\"50\" y=\"100\" font-size=\"40\">ประหยัดไฟ</text></svg>");

        assertEquals("image/svg+xml", store.read(saved.filename()).contentType());
        assertEquals("svg", saved.filename().substring(saved.filename().lastIndexOf('.') + 1));
        var response = new GeneratedImageController(store).image(saved.filename());
        assertEquals("image/svg+xml", response.getHeaders().getContentType().toString());
        assertEquals("default-src 'none'; script-src 'none'; style-src 'none'; object-src 'none'",
                response.getHeaders().getFirst("Content-Security-Policy"));
        assertThrows(ImageGenerationException.class,
                () -> store.saveSvg(prefix + "<script>alert(1)</script></svg>"));
        assertThrows(ImageGenerationException.class,
                () -> store.saveSvg(prefix + "<image href=\"https://example.com/a.png\"/></svg>"));
        assertThrows(ImageGenerationException.class,
                () -> store.saveSvg(prefix + "<rect fill=\"url(https://example.com/a)\"/></svg>"));
        assertThrows(ImageGenerationException.class,
                () -> store.saveSvg("<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>"
                        + prefix + "<text>&x;</text></svg>"));
        assertThrows(ImageGenerationException.class,
                () -> SafeSvg.sanitize(prefix + "<text x=\"50\" y=\"100\">ยอดขาย 42%</text></svg>",
                        "ทำกราฟยอดขาย"));
    }

    @Test
    void acceptsAdaptiveCanvasHeightButRejectsMismatchedAndExcessiveDimensions() {
        var store = new GeneratedImageStore(directory, 128_000, Clock.systemUTC());
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1200\" height=\"1400\" viewBox=\"0 0 1200 1400\"></svg>";
        var image = store.saveSvg(svg);
        assertEquals(1200, image.width());
        assertEquals(1400, image.height());
        assertEquals("image/svg+xml", store.read(image.filename()).contentType());
        assertThrows(ImageGenerationException.class, () -> store.saveSvg(svg.replace("1400", "9999")));
        assertThrows(ImageGenerationException.class, () -> store.saveSvg(svg.replace("viewBox=\"0 0 1200 1400\"", "viewBox=\"0 0 1200 800\"")));
        assertThrows(ImageGenerationException.class, () -> store.saveSvg(svg.replace("1400", "NaN")));

        var generator = new SvgGraphicGenerator(request -> """
                {"height":1400,"elements":[{"type":"text","x":60,"y":1300,
                "fontSize":24,"text":"เนื้อหาด้านล่าง"}]}
                """, store, new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(1400, generator.generate("ทำแผนภาพแนวตั้ง").height());
    }

    @Test
    void asksGemmaToRepairUnsupportedSvgOnce() {
        GeneratedImageStore store = new GeneratedImageStore(directory, 128_000, Clock.systemUTC());
        AtomicInteger calls = new AtomicInteger();
        SvgGraphicGenerator generator = new SvgGraphicGenerator(request -> calls.incrementAndGet() == 1
                ? "{\"elements\":[{\"type\":\"rect\",\"x\":1100,\"y\":0,\"width\":300,\"height\":100}]}"
                : "{\"elements\":[{\"type\":\"text\",\"x\":50,\"y\":100,"
                        + "\"fontSize\":40,\"text\":\"ไฟ\",\"fill\":\"#222222\"}]}",
                store, new com.fasterxml.jackson.databind.ObjectMapper());

        var result = generator.generate("ทำแผนผังเรื่องไฟ");

        assertEquals(2, calls.get());
        assertEquals("image/svg+xml", store.read(result.filename()).contentType());
    }

    @Test
    void wrapsLongLabelsInsideTheCanvas() {
        GeneratedImageStore store = new GeneratedImageStore(directory, 128_000, Clock.systemUTC());
        SvgGraphicGenerator generator = new SvgGraphicGenerator(request ->
                "{\"elements\":[{\"type\":\"text\",\"x\":1000,\"y\":100,\"fontSize\":24,"
                        + "\"text\":\"ถอดปลั๊กอุปกรณ์เมื่อเลิกใช้งานเพื่อลดพลังงานแฝง\","
                        + "\"anchor\":\"center\"}]}",
                store, new com.fasterxml.jackson.databind.ObjectMapper());

        var saved = generator.generate("ทำแผนผังเรื่องถอดปลั๊กอุปกรณ์เมื่อเลิกใช้งานเพื่อลดพลังงานแฝง");
        String svg = new String(store.read(saved.filename()).bytes(), java.nio.charset.StandardCharsets.UTF_8);

        org.junit.jupiter.api.Assertions.assertTrue(svg.split("<text ").length > 2);
    }

    static byte[] png() {
        return new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    }
}
