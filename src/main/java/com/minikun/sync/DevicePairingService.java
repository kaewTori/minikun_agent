package com.minikun.sync;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class DevicePairingService {
    public static final String COOKIE_NAME = "MINIKUN_DEVICE";
    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final PairedDeviceRepository devices;
    private final Clock clock;
    private final SecureRandom random;
    private final String ownerId;
    private final String canonicalOrigin;
    private final Duration pairingTtl;
    private final Duration sessionTtl;
    private final Map<String, PendingPairing> pending = new ConcurrentHashMap<>();

    public DevicePairingService(PairedDeviceRepository devices, Clock clock, SecureRandom random,
            String ownerId, String canonicalOrigin, Duration pairingTtl, Duration sessionTtl) {
        this.devices = Objects.requireNonNull(devices);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
        this.ownerId = required(ownerId, "sync owner id", 200);
        this.canonicalOrigin = canonicalOrigin(canonicalOrigin);
        this.pairingTtl = positive(pairingTtl, "pairing ttl", Duration.ofMinutes(15));
        this.sessionTtl = positive(sessionTtl, "session ttl", Duration.ofDays(366));
    }

    public synchronized SessionView openSession(HttpServletRequest request, HttpServletResponse response, String deviceName) {
        requireSecure(request);
        Optional<Session> existing = resolve(request);
        if (existing.isPresent()) return view(existing.get(), false);
        boolean canBootstrap = isLoopbackOrigin(request) && fromThisHost(request.getRemoteAddr())
                && devices.activeCount(clock.instant()) == 0;
        if (!canBootstrap) return new SessionView(false, false, null, "", ownerId, canonicalOrigin);
        Session created = issue(ownerId, name(deviceName, "Mac เครื่องหลัก"), response);
        return view(created, true);
    }

    public Optional<Session> resolve(HttpServletRequest request) {
        String token = cookie(request);
        if (token.isBlank()) return Optional.empty();
        Instant now = clock.instant();
        Optional<PairedDevice> found = devices.findActiveByTokenHash(hash(token), now);
        found.ifPresent(device -> devices.touch(device.id(), now));
        return found.map(device -> new Session(device.id(), device.ownerId(), device.name()));
    }

    public Session require(HttpServletRequest request) {
        return resolve(request).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "อุปกรณ์นี้ยังไม่ได้จับคู่กับมินิคุง"));
    }

    public PairingView createPairing(Session creator) {
        cleanExpired();
        String code;
        do code = code(); while (pending.containsKey(code));
        Instant expiresAt = clock.instant().plus(pairingTtl);
        pending.put(code, new PendingPairing(code, creator.ownerId(), creator.deviceId(), expiresAt));
        return new PairingView(code, expiresAt, canonicalOrigin + "/pair?code=" + code,
                "/v1/sync/pairings/" + code + "/qr");
    }

    public String pairingUrl(Session creator, String code) {
        PendingPairing pairing = activePairing(code);
        if (!pairing.ownerId().equals(creator.ownerId()) || !pairing.creatorDeviceId().equals(creator.deviceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "pairing code is unavailable");
        }
        return canonicalOrigin + "/pair?code=" + pairing.code();
    }

    public SessionView claim(HttpServletRequest request, HttpServletResponse response, String code, String deviceName) {
        requireSecure(request);
        String normalized = normalizeCode(code);
        PendingPairing pairing = activePairing(normalized);
        if (!pending.remove(normalized, pairing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "pairing code was already used");
        }
        Session created = issue(pairing.ownerId(), name(deviceName, "อุปกรณ์ใหม่"), response);
        return view(created, false);
    }

    public List<DeviceView> list(Session current) {
        return devices.list(current.ownerId()).stream()
                .filter(device -> device.expiresAt().isAfter(clock.instant()))
                .map(device -> new DeviceView(
                device.id(), device.name(), device.createdAt(), device.lastSeenAt(),
                device.id().equals(current.deviceId()))).toList();
    }

    public boolean revoke(Session current, UUID deviceId) {
        if (current.deviceId().equals(deviceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "ไม่สามารถถอนสิทธิ์อุปกรณ์ที่กำลังใช้งานได้");
        }
        return devices.revoke(current.ownerId(), deviceId, clock.instant());
    }

    public void disconnect(Session current, HttpServletResponse response) {
        devices.revoke(current.ownerId(), current.deviceId(), clock.instant());
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(Duration.ZERO).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private Session issue(String owner, String name, HttpServletResponse response) {
        Instant now = clock.instant();
        String token = token();
        PairedDevice device = new PairedDevice(UUID.randomUUID(), owner, name, hash(token),
                now, now, now.plus(sessionTtl), null);
        devices.save(device);
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true).secure(true).sameSite("Strict").path("/")
                .maxAge(sessionTtl).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return new Session(device.id(), device.ownerId(), device.name());
    }

    private SessionView view(Session session, boolean bootstrapped) {
        return new SessionView(true, bootstrapped, session.deviceId(), session.deviceName(),
                session.ownerId(), canonicalOrigin);
    }

    private PendingPairing activePairing(String value) {
        String normalized = normalizeCode(value);
        PendingPairing pairing = pending.get(normalized);
        if (pairing == null || !pairing.expiresAt().isAfter(clock.instant())) {
            pending.remove(normalized);
            throw new ResponseStatusException(HttpStatus.GONE, "pairing code หมดอายุแล้วครับ");
        }
        return pairing;
    }

    private void cleanExpired() {
        Instant now = clock.instant();
        pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private boolean fromThisHost(String remoteAddress) {
        try {
            InetAddress remote = InetAddress.getByName(remoteAddress);
            if (remote.isLoopbackAddress()) return true;
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress local : Collections.list(network.getInetAddresses())) {
                    if (local.equals(remote)) return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private boolean isLoopbackOrigin(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getServerName()).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void requireSecure(HttpServletRequest request) {
        if (!request.isSecure()) {
            throw new ResponseStatusException(HttpStatus.UPGRADE_REQUIRED,
                    "Device pairing ใช้งานได้เฉพาะ HTTPS เท่านั้นครับ");
        }
    }

    private String cookie(HttpServletRequest request) {
        if (request.getCookies() == null) return "";
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) return Objects.requireNonNullElse(cookie.getValue(), "");
        }
        return "";
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String code() {
        char[] value = new char[8];
        for (int index = 0; index < value.length; index++) {
            value[index] = CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)];
        }
        return new String(value);
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String normalizeCode(String value) {
        String normalized = Objects.requireNonNullElse(value, "").replace("-", "")
                .replace(" ", "").toUpperCase(Locale.ROOT);
        if (normalized.length() != 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pairing code ไม่ถูกต้อง");
        }
        return normalized;
    }

    private String name(String value, String fallback) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank()) return fallback;
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private String required(String value, String label, int limit) {
        String normalized = Objects.requireNonNullElse(value, "").trim();
        if (normalized.isBlank() || normalized.length() > limit) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private String canonicalOrigin(String value) {
        String origin = required(value, "canonical origin", 500).replaceAll("/+$", "");
        if (!origin.startsWith("https://")) throw new IllegalArgumentException("canonical origin must use HTTPS");
        return origin;
    }

    private Duration positive(Duration value, String label, Duration maximum) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    public record Session(UUID deviceId, String ownerId, String deviceName) { }

    public record SessionView(boolean paired, boolean bootstrapped, UUID deviceId,
            String deviceName, String ownerId, String canonicalOrigin) { }

    public record PairingView(String code, Instant expiresAt, String pairUrl, String qrUrl) { }

    public record DeviceView(UUID id, String name, Instant createdAt, Instant lastSeenAt, boolean current) { }

    private record PendingPairing(String code, String ownerId, UUID creatorDeviceId, Instant expiresAt) { }
}
