package com.minikun.ups;

import static org.junit.jupiter.api.Assertions.*;

import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.tools.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class NutUpsClientTest {
    private static final String LIVE = """
            BEGIN LIST VAR cleanline
            VAR cleanline ups.status "OL"
            VAR cleanline ups.load "18"
            VAR cleanline battery.charge "100"
            VAR cleanline input.voltage "222.3"
            VAR cleanline output.voltage "224.4"
            VAR cleanline ups.model "Cleanline \\"D\\" \\\\ USB"
            END LIST VAR cleanline
            """;

    @Test
    void readsOnlyOneListRequestAndParsesEscapedValues() throws Exception {
        try (var server = new FakeNut(LIVE, 0)) {
            var snapshot = server.client(Duration.ofSeconds(1)).read();
            assertTrue(snapshot.available());
            assertEquals("OL", snapshot.variables().get("ups.status"));
            assertEquals("Cleanline \"D\" \\ USB", snapshot.variables().get("ups.model"));
            assertNotNull(snapshot.queriedAt());
        }
    }

    @Test
    void rejectsStaleIncompleteForeignAndOversizedResponses() throws Exception {
        for (String response : List.of("ERR DATA-STALE\n",
                "BEGIN LIST VAR cleanline\nVAR cleanline ups.status \"OL\"\n",
                "BEGIN LIST VAR cleanline\nVAR other ups.status \"OL\"\nEND LIST VAR cleanline\n",
                "BEGIN LIST VAR cleanline\nEND LIST VAR cleanline\n",
                "BEGIN LIST VAR cleanline\n" + "x".repeat(4097) + "\n")) {
            try (var server = new FakeNut(response, 0)) {
                var snapshot = server.client(Duration.ofSeconds(1)).read();
                assertFalse(snapshot.available());
                assertTrue(snapshot.variables().isEmpty(), "No partial or stale measurements may escape");
                if (response.startsWith("ERR DATA-STALE")) assertEquals("DATA-STALE", snapshot.error());
            }
        }
    }

    @Test
    void timesOutAndRejectsCommandInjectionInConfiguration() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new NutUpsClient("localhost", 3493,
                "cleanline\nINSTCMD cleanline load.off", Duration.ofSeconds(1)));
        try (var server = new FakeNut(LIVE, 200)) {
            assertTimeout(Duration.ofSeconds(1), () -> {
                assertFalse(server.client(Duration.ofMillis(50)).read().available());
            });
        }
    }

    @Test
    void routesLiveThaiStatusThroughTheToolAndLeavesAdviceAndControlsAlone() throws Exception {
        try (var server = new FakeNut(LIVE, 0)) {
            var tool = new UpsStatusTool(server.client(Duration.ofSeconds(1)));
            var conversation = new ConversationId("ups-test");
            assertTrue(tool.route("ควรซื้อ UPS แบบไหน", conversation).isEmpty());
            assertTrue(tool.route("เปรียบเทียบสถานะ UPS สองรุ่น", conversation).isEmpty());
            assertTrue(tool.route("ตรวจ UPS แล้วปิดเครื่อง", conversation).isEmpty());
            assertFalse(tool.requiresExplicitConfirmation(Map.of()));
            var evidence = tool.route("เช็กสถานะ UPS ให้หน่อย", conversation).orElseThrow();
            assertTrue(evidence.success());
            assertTrue(evidence.finalResponse());
            assertTrue(evidence.content().contains("222.3 V"));
            assertTrue(evidence.content().contains("ใช้ไฟบ้าน"));
            assertTrue(evidence.content().contains("อาจเป็นค่าประเมิน"));
            assertTrue(evidence.content().contains("ยังไม่มีเวลาสำรอง"));
            assertTrue(evidence.content().contains("ยังไม่มีวัตต์ที่วัดจริง"));
            assertTrue(evidence.content().contains("สุขภาพแบตเตอรี่ยังไม่ทราบ"));
        }
        try (var server = new FakeNut("ERR DRIVER-NOT-CONNECTED\n", 0)) {
            var tool = new UpsStatusTool(server.client(Duration.ofSeconds(1)));
            var executor = new DefaultToolExecutor(new DefaultToolRegistry(List.of(tool)));
            var result = executor.execute(new ToolCallContext(new ConversationId("c"), "ups"),
                    new ToolCall("ups", "ups.status", Map.of()));
            assertFalse(result.success());
            assertTrue(result.error().contains("DRIVER-NOT-CONNECTED"));
        }
        try (var server = new FakeNut(LIVE, 0)) {
            var tool = new UpsStatusTool(server.client(Duration.ofSeconds(1)));
            var evidence = tool.route("UPS สำรองไฟได้นานแค่ไหน", new ConversationId("ups-runtime-test"))
                    .orElseThrow();
            assertTrue(evidence.content().contains("ยังไม่มีเวลาสำรอง"));
        }
    }

    @Test
    void reportsMeasuredPowerSeparatelyFromRatedPowerAndRoutesTemperatureQuestions() throws Exception {
        String response = LIVE.replace("END LIST VAR cleanline", """
                VAR cleanline ups.realpower "83"
                VAR cleanline ups.realpower.nominal "1200"
                VAR cleanline ups.temperature "31.2"
                VAR cleanline battery.runtime "540"
                END LIST VAR cleanline""");
        try (var server = new FakeNut(response, 0)) {
            var tool = new UpsStatusTool(server.client(Duration.ofSeconds(1)));
            var evidence = tool.route("อุณหภูมิ UPS ตอนนี้เท่าไหร่", new ConversationId("ups-metrics-test"))
                    .orElseThrow();
            assertTrue(evidence.content().contains("โหลด: 18%"));
            assertTrue(evidence.content().contains("กำลังไฟจริงที่ UPS รายงาน: 83 W"));
            assertTrue(evidence.content().contains("กำลังไฟพิกัด (สเปก): 1200 W"));
            assertTrue(evidence.content().contains("31.2 °C"));
            assertTrue(evidence.content().contains("540 วินาที"));
            assertFalse(evidence.content().contains("ยังไม่มีวัตต์ที่วัดจริง"));
        }
    }

    @Test
    void configurationRegistersToolRouterAndControllerAndCanBeDisabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(UpsConfiguration.class)
                .withPropertyValues("minikun.ups.monitor.enabled=false")
                .withInitializer(context -> context.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()));
        runner.run(context -> {
            assertEquals(1, context.getBeansOfType(Tool.class).size());
            assertEquals(1, context.getBeansOfType(ToolRequestRouter.class).size());
            assertEquals(1, context.getBeansOfType(UpsStatusController.class).size());
        });
        runner.withPropertyValues("minikun.ups.enabled=false").run(context -> {
                    assertTrue(context.getBeansOfType(NutUpsClient.class).isEmpty());
                });
    }

    private static final class FakeNut implements AutoCloseable {
        private final ServerSocket listener = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        private final AtomicReference<String> request = new AtomicReference<>();
        private final Thread worker;

        FakeNut(String response, long delayMillis) throws Exception {
            worker = Thread.ofVirtual().start(() -> {
                try (var socket = listener.accept()) {
                    var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    request.set(input.readLine());
                    Thread.sleep(delayMillis);
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { /* Client timeout and rejection deliberately close the socket. */ }
            });
        }

        NutUpsClient client(Duration timeout) {
            return new NutUpsClient(listener.getInetAddress().getHostAddress(), listener.getLocalPort(), "cleanline", timeout);
        }

        @Override public void close() throws Exception {
            listener.close();
            worker.join(1000);
            assertFalse(worker.isAlive());
            assertEquals("LIST VAR cleanline", request.get());
        }
    }
}
