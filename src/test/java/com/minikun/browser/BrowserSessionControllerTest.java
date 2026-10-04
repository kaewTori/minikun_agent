package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.sync.DevicePairingService;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class BrowserSessionControllerTest {
    private final BrowserSessionClient session = new BrowserSessionClient(new ObjectMapper(),
            Path.of("/missing/python"), Path.of("/missing/script"), Path.of("/tmp/profile"), Duration.ofSeconds(1));
    private final ManagedBrowserContentClient cache = new ManagedBrowserContentClient(
            url -> { throw new AssertionError("must not render"); }, Duration.ZERO, Duration.ZERO, 1, 1);
    private final DevicePairingService pairing = mock(DevicePairingService.class);
    private final StaticListableBeanFactory beans = new StaticListableBeanFactory(java.util.Map.of("pairing", pairing));
    private final BrowserSessionController controller = new BrowserSessionController(session, cache,
            new BrowserContentService(cache, true, 5), beans.getBeanProvider(DevicePairingService.class), true, true);

    @Test
    void rejectsCrossOriginAndCrossSiteActionsBeforeOpeningBrowser() {
        var request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://attacker.example");
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> controller.open(request, new BrowserSessionController.PageRequest("https://example.com", ""))).getStatusCode().value());
        verifyNoInteractions(pairing);
        MockHttpServletRequest crossSite = new MockHttpServletRequest();
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> controller.close(crossSite)).getStatusCode().value());
    }

    @Test
    void rejectsPrivateUrlsAndCredentialsEvenForPairedDevice() {
        var request = new MockHttpServletRequest();
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.open(request, new BrowserSessionController.PageRequest("http://127.0.0.1", ""))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> controller.open(request, new BrowserSessionController.PageRequest("https://user:secret@example.com", ""))).getStatusCode().value());
        assertFalse(controller.status(request).get("available").equals(true));
    }

    @Test
    void requiresPairedDeviceAndOpenSessionBeforeReading() {
        var request = new MockHttpServletRequest();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED)).when(pairing).require(request);
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> controller.status(request)).getStatusCode().value());
        reset(pairing);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> controller.read(request, new BrowserSessionController.PageRequest("https://1.1.1.1", ""))).getStatusCode().value());
    }
}
