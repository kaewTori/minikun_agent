package com.minikun.sync;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/v1/sync")
public final class SyncController {
    private final DevicePairingService pairing;
    private final ChatSyncService conversations;
    private final SyncEventBroker events;

    public SyncController(DevicePairingService pairing, ChatSyncService conversations, SyncEventBroker events) {
        this.pairing = pairing;
        this.conversations = conversations;
        this.events = events;
    }

    @PostMapping("/session")
    public DevicePairingService.SessionView session(HttpServletRequest request, HttpServletResponse response,
            @RequestBody(required = false) SessionRequest body) {
        return pairing.openSession(request, response, body == null ? "" : body.deviceName());
    }

    @DeleteMapping("/session")
    public ResponseEntity<Void> disconnect(HttpServletRequest request, HttpServletResponse response) {
        DevicePairingService.Session session = pairing.require(request);
        pairing.disconnect(session, response);
        events.publish(session.ownerId(), "device", session.deviceId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/devices")
    public List<DevicePairingService.DeviceView> devices(HttpServletRequest request) {
        return pairing.list(pairing.require(request));
    }

    @DeleteMapping("/devices/{deviceId}")
    public Map<String, Object> revoke(HttpServletRequest request, @PathVariable UUID deviceId) {
        DevicePairingService.Session session = pairing.require(request);
        boolean revoked = pairing.revoke(session, deviceId);
        if (revoked) events.publish(session.ownerId(), "device", session.deviceId());
        return Map.of("revoked", revoked, "deviceId", deviceId);
    }

    @PostMapping("/pairings")
    public DevicePairingService.PairingView createPairing(HttpServletRequest request) {
        return pairing.createPairing(pairing.require(request));
    }

    @GetMapping(value = "/pairings/{code}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> pairingQr(HttpServletRequest request, @PathVariable String code) throws Exception {
        String url = pairing.pairingUrl(pairing.require(request), code);
        BitMatrix matrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                        EncodeHintType.MARGIN, 1));
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) image.setRGB(x, y, matrix.get(x, y) ? 0x07100B : 0xFFFFFF);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(output.toByteArray());
    }

    @PostMapping("/pairings/claim")
    public DevicePairingService.SessionView claim(HttpServletRequest request, HttpServletResponse response,
            @RequestBody ClaimRequest body) {
        return pairing.claim(request, response, body.code(), body.deviceName());
    }

    @GetMapping("/conversations")
    public List<ChatSyncConversation> conversations(HttpServletRequest request) {
        return conversations.list(pairing.require(request));
    }

    @PutMapping("/conversations/{conversationId}")
    public ChatSyncConversation save(HttpServletRequest request, @PathVariable String conversationId,
            @RequestBody ChatSyncConversation body) {
        return conversations.save(pairing.require(request), conversationId, body);
    }

    @DeleteMapping("/conversations/{conversationId}")
    public Map<String, Object> deleteConversation(HttpServletRequest request,
            @PathVariable String conversationId) {
        boolean deleted = conversations.delete(pairing.require(request), conversationId);
        return Map.of("deleted", deleted, "conversationId", conversationId);
    }

    @PostMapping("/conversations/import")
    public Map<String, Integer> importConversations(HttpServletRequest request,
            @RequestBody List<ChatSyncConversation> body) {
        return Map.of("imported", conversations.importAll(pairing.require(request), body));
    }

    @DeleteMapping("/conversations")
    public ResponseEntity<Void> deleteConversations(HttpServletRequest request) {
        conversations.deleteAll(pairing.require(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventStream(HttpServletRequest request) {
        DevicePairingService.Session session = pairing.require(request);
        return events.connect(session.ownerId());
    }

    public record SessionRequest(String deviceName) { }
    public record ClaimRequest(String code, String deviceName) { }
}
