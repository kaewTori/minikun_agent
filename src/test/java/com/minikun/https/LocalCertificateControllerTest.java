package com.minikun.https;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class LocalCertificateControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void servesPublicCaAsDownload() throws Exception {
        byte[] expected = {1, 2, 3};
        Path certificate = Files.write(tempDir.resolve("ca.cer"), expected);

        ResponseEntity<byte[]> response = new LocalCertificateController(certificate.toString()).certificate();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/x-x509-ca-cert", response.getHeaders().getContentType().toString());
        assertEquals("attachment; filename=\"minikun-local-ca.cer\"",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(expected, response.getBody());
    }

    @Test
    void returnsNotFoundWhenCaIsUnavailable() throws Exception {
        ResponseEntity<byte[]> response = new LocalCertificateController(
                tempDir.resolve("missing.cer").toString()).certificate();

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
