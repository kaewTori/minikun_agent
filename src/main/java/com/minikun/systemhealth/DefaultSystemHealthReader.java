package com.minikun.systemhealth;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Java/JMX-based host probe with a fixed, application-owned dependency allowlist. */
public final class DefaultSystemHealthReader implements SystemHealthReader {
    private final Path diskPath;
    private final List<SystemHealthDependency> dependencies;
    private final int connectTimeoutMillis;
    private final double memoryWarningPercent;
    private final double diskWarningPercent;

    public DefaultSystemHealthReader(
            Path diskPath,
            List<SystemHealthDependency> dependencies,
            Duration connectTimeout,
            double memoryWarningPercent,
            double diskWarningPercent) {
        if (diskPath == null || dependencies == null || connectTimeout == null) {
            throw new IllegalArgumentException("system health configuration must not be null");
        }
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("system health connect timeout must be positive");
        }
        validatePercent("memory warning", memoryWarningPercent);
        validatePercent("disk warning", diskWarningPercent);
        this.diskPath = diskPath.toAbsolutePath().normalize();
        this.dependencies = List.copyOf(dependencies);
        this.connectTimeoutMillis = Math.max(1, Math.toIntExact(Math.min(connectTimeout.toMillis(), Integer.MAX_VALUE)));
        this.memoryWarningPercent = memoryWarningPercent;
        this.diskWarningPercent = diskWarningPercent;
    }

    @Override
    public SystemHealthReport read() {
        Map<String, Object> cpu = cpu();
        Map<String, Object> memory = memory();
        Map<String, Object> swap = swap();
        Map<String, Object> disk = disk();
        Map<String, Object> jvm = jvm();
        Map<String, Object> process = process();
        Map<String, Map<String, Object>> dependencyStatus = dependencies();

        boolean warning = isWarning(cpu) || isWarning(memory) || isWarning(swap) || isWarning(disk)
                || dependencyStatus.values().stream().anyMatch(this::isWarning);
        return new SystemHealthReport(
                warning ? "WARNING" : "UP",
                !warning,
                cpu, memory, swap, disk, jvm, process, dependencyStatus);
    }

    private Map<String, Object> cpu() {
        OperatingSystemMXBean operatingSystem = ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("logical_processors", operatingSystem.getAvailableProcessors());

        if (operatingSystem instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                putPercent(result, "usage_percent", extended.getCpuLoad());
                putPercent(result, "process_usage_percent", extended.getProcessCpuLoad());
            } catch (RuntimeException | InternalError exception) {
                result.put("status", "UNKNOWN");
            }
        }
        putFinite(result, "load_average", operatingSystem.getSystemLoadAverage());
        if (number(result, "usage_percent") > 90.0) {
            result.put("status", "WARNING");
        }
        return result;
    }

    private Map<String, Object> memory() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                addUsage(result, extended.getTotalMemorySize(), extended.getFreeMemorySize());
            } catch (RuntimeException | InternalError exception) {
                result.put("status", "UNKNOWN");
            }
        } else {
            result.put("status", "UNKNOWN");
        }
        markResourceWarning(result, memoryWarningPercent);
        return result;
    }

    private Map<String, Object> swap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                addUsage(result, extended.getTotalSwapSpaceSize(), extended.getFreeSwapSpaceSize());
            } catch (RuntimeException | InternalError exception) {
                result.put("status", "UNKNOWN");
            }
        } else {
            result.put("status", "UNKNOWN");
        }
        markResourceWarning(result, memoryWarningPercent);
        return result;
    }

    private Map<String, Object> disk() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", diskPath.toString());
        try {
            FileStore store = Files.getFileStore(diskPath);
            addUsage(result, store.getTotalSpace(), store.getUsableSpace());
            result.putIfAbsent("status", "UP");
            markResourceWarning(result, diskWarningPercent);
        } catch (IOException | RuntimeException exception) {
            result.put("status", "DOWN");
            result.put("error", "disk information is unavailable");
        }
        return result;
    }

    private Map<String, Object> jvm() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("java_version", System.getProperty("java.version", "unknown"));
        result.put("os", System.getProperty("os.name", "unknown"));
        result.put("os_version", System.getProperty("os.version", "unknown"));
        result.put("architecture", System.getProperty("os.arch", "unknown"));
        result.put("uptime_ms", runtime.getUptime());
        putNonNegative(result, "heap_used_bytes", heap.getUsed());
        putNonNegative(result, "heap_max_bytes", heap.getMax());
        return result;
    }

    private Map<String, Object> process() {
        ProcessHandle process = ProcessHandle.current();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", process.isAlive() ? "RUNNING" : "STOPPED");
        result.put("pid", process.pid());
        process.info().command().ifPresent(command -> result.put("command", Path.of(command).getFileName().toString()));
        return result;
    }

    private Map<String, Map<String, Object>> dependencies() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (SystemHealthDependency dependency : dependencies) {
            result.put(dependency.name(), check(dependency));
        }
        return result;
    }

    private Map<String, Object> check(SystemHealthDependency dependency) {
        long started = System.nanoTime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("port", dependency.port());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(dependency.host(), dependency.port()), connectTimeoutMillis);
            result.put("status", "UP");
        } catch (IOException | RuntimeException exception) {
            result.put("status", "DOWN");
        }
        result.put("latency_ms", Math.max(0, (System.nanoTime() - started) / 1_000_000));
        return result;
    }

    private void addUsage(Map<String, Object> result, long total, long free) {
        if (total <= 0 || free < 0 || free > total) {
            result.put("status", "UNKNOWN");
            return;
        }
        result.put("total_bytes", total);
        result.put("free_bytes", free);
        result.put("used_bytes", total - free);
        result.put("used_percent", round((total - free) * 100.0 / total));
    }

    private void markResourceWarning(Map<String, Object> result, double threshold) {
        if (number(result, "used_percent") >= threshold) {
            result.put("status", "WARNING");
        }
    }

    private boolean isWarning(Map<String, Object> result) {
        Object status = result.get("status");
        return !"UP".equals(status) && !"RUNNING".equals(status);
    }

    private double number(Map<String, Object> result, String key) {
        Object value = result.get(key);
        return value instanceof Number number ? number.doubleValue() : -1;
    }

    private void putPercent(Map<String, Object> result, String key, double value) {
        putFinite(result, key, value < 0 ? -1 : value * 100.0);
    }

    private void putFinite(Map<String, Object> result, String key, double value) {
        if (Double.isFinite(value) && value >= 0) {
            result.put(key, round(value));
        }
    }

    private void putNonNegative(Map<String, Object> result, String key, long value) {
        if (value >= 0) {
            result.put(key, value);
        }
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void validatePercent(String name, double value) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " threshold must be between 0 and 100");
        }
    }
}
