package com.minikun.sync;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.Cookie;

class DevicePairingServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void bootstrapsOnlyARequestFromTheHostAndIssuesProtectedCookie() {
        InMemoryDevices devices = new InMemoryDevices();
        DevicePairingService service = service(devices);
        MockHttpServletRequest local = secure("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        DevicePairingService.SessionView session = service.openSession(local, response, "Mac");

        assertTrue(session.paired());
        assertTrue(session.bootstrapped());
        String cookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));

        DevicePairingService otherService = service(new InMemoryDevices());
        DevicePairingService.SessionView remote = otherService.openSession(
                secure("192.0.2.40"), new MockHttpServletResponse(), "iPhone");
        assertFalse(remote.paired());
    }

    @Test
    void bootstrapsOnlyOnTheLoopbackOriginEvenWhenAnotherDeviceExists() {
        InMemoryDevices devices = new InMemoryDevices();
        DevicePairingService service = service(devices);
        MockHttpServletRequest tailscaleHost = secure("127.0.0.1");
        tailscaleHost.setServerName("mini-kun");

        DevicePairingService.SessionView child = service.openSession(
                tailscaleHost, new MockHttpServletResponse(), "Mac ผ่าน Tailscale");
        assertFalse(child.paired());

        DevicePairingService.SessionView primary = service.openSession(
                secure("127.0.0.1"), new MockHttpServletResponse(), "Mac เครื่องหลัก");
        assertTrue(primary.paired());

        DevicePairingService.SessionView recoveredPrimary = service.openSession(
                secure("127.0.0.1"), new MockHttpServletResponse(), "Mac เครื่องหลักอีก session");
        assertTrue(recoveredPrimary.paired());
        assertTrue(recoveredPrimary.bootstrapped());
    }

    @Test
    void pairingCodeCanBeClaimedOnlyOnce() {
        InMemoryDevices devices = new InMemoryDevices();
        DevicePairingService service = service(devices);
        MockHttpServletResponse bootstrapResponse = new MockHttpServletResponse();
        service.openSession(secure("127.0.0.1"), bootstrapResponse, "Mac");
        MockHttpServletRequest trustedRequest = secure("127.0.0.1");
        trustedRequest.setCookies(cookie(bootstrapResponse));
        DevicePairingService.Session trusted = service.require(trustedRequest);
        DevicePairingService.PairingView pairing = service.createPairing(trusted);

        MockHttpServletResponse claimResponse = new MockHttpServletResponse();
        DevicePairingService.SessionView claimed = service.claim(
                secure("192.0.2.40"), claimResponse, pairing.code(), "iPhone");

        assertTrue(claimed.paired());
        assertTrue(claimResponse.getHeader(HttpHeaders.SET_COOKIE).contains(DevicePairingService.COOKIE_NAME));
        assertThrows(ResponseStatusException.class, () -> service.claim(
                secure("192.0.2.40"), new MockHttpServletResponse(), pairing.code(), "อีกเครื่อง"));
    }

    @Test
    void refusesPairingOverPlainHttp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        assertThrows(ResponseStatusException.class, () -> service(new InMemoryDevices())
                .openSession(request, new MockHttpServletResponse(), "Mac"));
    }

    @Test
    void disconnectRevokesCurrentDeviceAndClearsCookie() {
        InMemoryDevices devices = new InMemoryDevices();
        DevicePairingService service = service(devices);
        MockHttpServletResponse bootstrapResponse = new MockHttpServletResponse();
        service.openSession(secure("127.0.0.1"), bootstrapResponse, "Mac");
        MockHttpServletRequest request = secure("127.0.0.1");
        request.setCookies(cookie(bootstrapResponse));
        DevicePairingService.Session session = service.require(request);
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.disconnect(session, response);

        assertFalse(service.resolve(request).isPresent());
        String cleared = response.getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(cleared.contains(DevicePairingService.COOKIE_NAME + "="));
        assertTrue(cleared.contains("Max-Age=0"));
    }

    private DevicePairingService service(InMemoryDevices devices) {
        return new DevicePairingService(devices, clock, new SecureRandom(), "default",
                "https://mini-kun:8443", Duration.ofMinutes(2), Duration.ofDays(180));
    }

    private MockHttpServletRequest secure(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private Cookie cookie(MockHttpServletResponse response) {
        String header = response.getHeader(HttpHeaders.SET_COOKIE);
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        return new Cookie(DevicePairingService.COOKIE_NAME, value);
    }

    private static final class InMemoryDevices implements PairedDeviceRepository {
        private final List<PairedDevice> values = new ArrayList<>();

        @Override public long activeCount(Instant now) {
            return values.stream().filter(value -> value.revokedAt() == null && value.expiresAt().isAfter(now)).count();
        }

        @Override public Optional<PairedDevice> findActiveByTokenHash(String hash, Instant now) {
            return values.stream().filter(value -> value.tokenHash().equals(hash)
                    && value.revokedAt() == null && value.expiresAt().isAfter(now)).findFirst();
        }

        @Override public void save(PairedDevice device) { values.add(device); }
        @Override public void touch(UUID id, Instant seenAt) { }
        @Override public List<PairedDevice> list(String ownerId) {
            return values.stream().filter(value -> value.ownerId().equals(ownerId) && value.revokedAt() == null).toList();
        }
        @Override public boolean revoke(String ownerId, UUID id, Instant revokedAt) {
            for (int index = 0; index < values.size(); index++) {
                PairedDevice value = values.get(index);
                if (value.ownerId().equals(ownerId) && value.id().equals(id) && value.revokedAt() == null) {
                    values.set(index, new PairedDevice(value.id(), value.ownerId(), value.name(), value.tokenHash(),
                            value.createdAt(), value.lastSeenAt(), value.expiresAt(), revokedAt));
                    return true;
                }
            }
            return false;
        }
    }
}
