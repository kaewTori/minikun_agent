package com.minikun.visual;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ImageProxyServiceTest {
    private final ImageProxyService service = new ImageProxyService(Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofMinutes(1), 1024, 2, Clock.systemUTC());

    @Test void rejectsPlainHttpAndLocalTargetsBeforeDownloading() {
        assertThrows(ImageProxyException.class, () -> service.fetch("http://example.com/image.jpg"));
        assertThrows(ImageProxyException.class, () -> service.fetch("https://localhost/image.jpg"));
        assertThrows(ImageProxyException.class, () -> service.fetch("file:///tmp/image.jpg"));
    }
}
