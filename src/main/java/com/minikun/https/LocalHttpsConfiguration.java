package com.minikun.https;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adds a local HTTPS connector while preserving the existing HTTP connector. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "minikun.https.enabled", havingValue = "true")
public class LocalHttpsConfiguration {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> localHttpsConnector(
            @Value("${minikun.https.port:8443}") int port,
            @Value("${minikun.https.keystore}") String keystore,
            @Value("${minikun.https.keystore-password-file}") String passwordFile) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("local HTTPS port must be between 1 and 65535");
        }
        Path keystorePath = requiredFile("local HTTPS keystore", keystore);
        Path passwordPath = requiredFile("local HTTPS password file", passwordFile);
        return factory -> factory.addAdditionalConnectors(
                httpsConnector(port, keystorePath, passwordPath));
    }

    private Connector httpsConnector(int port, Path keystore, Path passwordFile) {
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setProperty("SSLEnabled", "true");

        SSLHostConfig ssl = new SSLHostConfig();
        ssl.setSslProtocol("TLS");
        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
                ssl, SSLHostConfigCertificate.Type.RSA);
        certificate.setCertificateKeyAlias("minikun");
        certificate.setCertificateKeystoreFile(keystore.toString());
        certificate.setCertificateKeystorePasswordFile(passwordFile.toString());
        certificate.setCertificateKeystoreType("PKCS12");
        ssl.addCertificate(certificate);
        connector.addSslHostConfig(ssl);
        return connector;
    }

    private Path requiredFile(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException(name + " is unavailable: " + path);
        }
        return path;
    }
}
