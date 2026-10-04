package com.minikun.browser;

import com.minikun.sync.DevicePairingService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/browser/session")
public final class BrowserSessionController {
    private final BrowserSessionClient session;
    private final ManagedBrowserContentClient cache;
    private final BrowserContentService browser;
    private final ObjectProvider<DevicePairingService> pairing;
    private final BrowserUrlPolicy policy;
    private final boolean enabled;

    public BrowserSessionController(BrowserSessionClient session, ManagedBrowserContentClient cache,
            BrowserContentService browser, ObjectProvider<DevicePairingService> pairing,
            @Value("${minikun.browser.enabled:true}") boolean enabled,
            @Value("${minikun.browser.block-private-addresses:true}") boolean blockPrivateAddresses) {
        this.session = session;
        this.cache = cache;
        this.browser = browser;
        this.pairing = pairing;
        this.enabled = enabled;
        this.policy = new BrowserUrlPolicy(blockPrivateAddresses);
    }

    @GetMapping
    public Map<String, Object> status(HttpServletRequest request) {
        authorize(request);
        return Map.of("available", enabled && session.available(), "activeHosts", session.activeHosts());
    }

    @PostMapping("/open")
    public Map<String, String> open(HttpServletRequest request, @RequestBody PageRequest page) {
        authorize(request);
        String url = validate(page);
        try {
            session.open(url);
            cache.invalidateHost(url);
            return Map.of("message", "เปิด browser บน Mac แล้ว ยืนยันหรือล็อกอิน จากนั้นกดอ่านต่อ", "url", url);
        } catch (BrowserContentException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @PostMapping("/read")
    public BrowserReadResult read(HttpServletRequest request, @RequestBody PageRequest page) {
        authorize(request);
        String url = validate(page);
        if (!session.handles(url)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "เปิดเว็บไซต์ใน browser session ก่อนครับ");
        }
        cache.invalidateHost(url);
        return browser.readUrls(List.of(url), 1, page.query());
    }

    @DeleteMapping
    public Map<String, String> close(HttpServletRequest request) {
        authorize(request);
        session.close();
        cache.clear();
        return Map.of("message", "ปิด browser แล้ว ข้อมูลล็อกอินยังเก็บใน profile แยกของมินิคุง");
    }

    private void authorize(HttpServletRequest request) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Browser is disabled");
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-site browser session requests are blocked");
        }
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() < 0 ? "https".equals(uri.getScheme()) ? 443 : 80 : uri.getPort();
                if (!request.getScheme().equals(uri.getScheme()) || !request.getServerName().equalsIgnoreCase(uri.getHost())
                        || request.getServerPort() != port) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-origin browser session requests are blocked");
            }
        }
        DevicePairingService devices = pairing.getIfAvailable();
        if (devices == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Pair this device first");
        devices.require(request);
    }

    private String validate(PageRequest page) {
        try {
            if (page.url() == null || page.url().length() > 8_192) throw new IllegalArgumentException();
            String url = page.url().strip();
            policy.validate(URI.create(url));
            return url;
        } catch (BrowserContentException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL ต้องเป็น HTTP/HTTPS ของเว็บไซต์สาธารณะ");
        }
    }

    public record PageRequest(String url, String query) { }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failure(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message",
                exception.getReason() == null ? "Browser session request failed" : exception.getReason()));
    }
}
