package com.minikun.https;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Serves only the public local CA certificate needed to trust Minikun on iPhone. */
@RestController
@ConditionalOnProperty(name = "minikun.https.enabled", havingValue = "true")
public final class LocalCertificateController {
    private static final MediaType X509_CA_CERT = MediaType.parseMediaType("application/x-x509-ca-cert");
    private final Path certificate;

    public LocalCertificateController(@Value("${minikun.https.ca-certificate}") String certificate) {
        this.certificate = Path.of(certificate).toAbsolutePath().normalize();
    }

    @GetMapping("/v1/system/https/ca")
    public ResponseEntity<byte[]> certificate() throws IOException {
        if (!Files.isRegularFile(certificate) || !Files.isReadable(certificate)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(X509_CA_CERT)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("minikun-local-ca.cer").build().toString())
                .body(Files.readAllBytes(certificate));
    }
}
