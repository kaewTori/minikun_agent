package com.minikun.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;

class LocalHttpsConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void addsSecureConnectorWithoutReplacingHttpConnector() throws Exception {
        Path keystore = Files.writeString(tempDir.resolve("minikun.p12"), "test");
        Path password = Files.writeString(tempDir.resolve("password.txt"), "secret");
        LocalHttpsConfiguration configuration = new LocalHttpsConfiguration();
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer =
                configuration.localHttpsConnector(8443, keystore.toString(), password.toString(), true);
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(8080);

        customizer.customize(factory);

        assertEquals(8080, factory.getPort());
        assertEquals(1, factory.getAdditionalConnectors().size());
        Connector connector = factory.getAdditionalConnectors().getFirst();
        assertEquals(8443, connector.getPort());
        assertEquals("https", connector.getScheme());
        assertTrue(connector.getSecure());
        assertEquals(Boolean.TRUE, connector.getProperty("SSLEnabled"));
        assertEquals("on", connector.getProperty("compression"));
    }

    @Test
    void rejectsUnavailableCertificateFiles() {
        LocalHttpsConfiguration configuration = new LocalHttpsConfiguration();

        assertThrows(IllegalArgumentException.class, () -> configuration.localHttpsConnector(
                8443, tempDir.resolve("missing.p12").toString(), tempDir.resolve("missing.txt").toString(), true));
    }
}
