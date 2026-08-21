package com.minikun.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Deterministic safety net applied after an LLM revises a technical answer. */
@Service
public final class CooperativeQualityGate {
    private static final Pattern JAVA_VERSION = Pattern.compile(
            "(?iu)\\b(?:jdk|java)\\s*[-:]?\\s*(\\d{1,3}(?:\\.\\d+)*)");
    private static final Pattern SPRING_BOOT_VERSION = Pattern.compile(
            "(?iu)\\bspring\\s*boot\\s*[-:]?\\s*(\\d{1,3}(?:\\.\\d+)*)");
    private static final Pattern APP_COUNT = Pattern.compile(
            "(?iu)(\\d[\\d,]*(?:\\.\\d+)?)\\s*(?:apps?|applications?|services?|instances?|"
                    + "แอป(?:พลิเคชัน)?|เซอร์วิส|อินสแตนซ์)");
    private static final Pattern MEMORY_VALUE = Pattern.compile(
            "(?iu)(\\d+(?:[.,]\\d+)?)\\s*(kb|mb|gb|tb|kib|mib|gib|tib)");
    private static final Pattern MEMORY_BEFORE_RAM = Pattern.compile(
            "(?iu)(\\d+(?:[.,]\\d+)?)\\s*(kb|mb|gb|tb|kib|mib|gib|tib)\\s*(?:of\\s+)?(?:ram|memory|หน่วยความจำ)");
    private static final Pattern RAM_BEFORE_MEMORY = Pattern.compile(
            "(?iu)(?:ram|memory|หน่วยความจำ)\\s*[:=]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(kb|mb|gb|tb|kib|mib|gib|tib)");
    private static final Pattern JVM_MEMORY_FLAG = Pattern.compile(
            "(?iu)(-xmx|-xx:maxmetaspacesize=|-xx:maxdirectmemorysize=|-xx:reservedcodecachesize=)"
                    + "\\s*(\\d+(?:\\.\\d+)?)\\s*([kmgt]i?b?|[kmgt])");
    private static final Pattern PER_INSTANCE_MEMORY = Pattern.compile(
            "(?iu)(\\d+(?:[.,]\\d+)?)\\s*(kb|mb|gb|tb|kib|mib|gib|tib)\\s*"
                    + "(?:per|ต่อ)\\s*(?:app|application|service|instance|แอป|เซอร์วิส|อินสแตนซ์)");
    private static final Pattern MEMORY_EQUATION = Pattern.compile(
            "(?iu)(\\d+(?:[.,]\\d+)?)\\s*[x×*]\\s*(\\d+(?:[.,]\\d+)?)\\s*"
                    + "(kb|mb|gb|tb|kib|mib|gib|tib)\\s*=\\s*(\\d+(?:[.,]\\d+)?)\\s*"
                    + "(kb|mb|gb|tb|kib|mib|gib|tib)");
    private static final Pattern INTERNAL_PROCESS_DISCLOSURE = Pattern.compile(
            "(?iu)(คำตอบร่าง|ร่างจาก\\s*(?:ollama|โมเดล)|ทบทวนคำตอบร่าง|"
                    + "draft\\s+answer|ollama\\s+draft|tinygrad\\s+review|specialist\\s+reviewer)");
    private static final List<String> EXCLUSIVE_GC_FLAGS = List.of(
            "-xx:+useg1gc", "-xx:+usezgc", "-xx:+useshenandoahgc", "-xx:+useparallelgc",
            "-xx:+useserialgc", "-xx:+useepsilongc");

    private final boolean enabled;
    private final double maxMemoryUtilization;
    private final double arithmeticTolerance;

    public CooperativeQualityGate(
            @Value("${minikun.model.cooperation.quality-gate.enabled:true}") boolean enabled,
            @Value("${minikun.model.cooperation.quality-gate.max-memory-utilization:0.85}")
            double maxMemoryUtilization,
            @Value("${minikun.model.cooperation.quality-gate.arithmetic-tolerance:0.08}")
            double arithmeticTolerance) {
        this.enabled = enabled;
        this.maxMemoryUtilization = bounded(maxMemoryUtilization, 0.5, 1.0, 0.85);
        this.arithmeticTolerance = bounded(arithmeticTolerance, 0.001, 0.25, 0.08);
    }

    public Decision evaluate(Prompt original, String draft, String revised) {
        if (!enabled) {
            return Decision.pass();
        }
        if (revised == null || revised.isBlank()) {
            return Decision.rejected(List.of("empty_revised_answer"));
        }

        String request = latestUserText(original);
        List<String> reasons = new ArrayList<>();
        requireVersion("java", firstGroup(JAVA_VERSION, request), JAVA_VERSION, revised, reasons);
        requireVersion("spring_boot", firstGroup(SPRING_BOOT_VERSION, request),
                SPRING_BOOT_VERSION, revised, reasons);

        Double appCount = firstNumber(APP_COUNT, request);
        if (appCount != null && !containsEquivalentNumber(APP_COUNT, revised, appCount)) {
            reasons.add("missing_workload_count:" + display(appCount));
        }

        Double totalMemoryMib = systemMemoryMib(request);
        if (totalMemoryMib != null && !containsEquivalentMemory(revised, totalMemoryMib)) {
            reasons.add("missing_total_memory:" + display(totalMemoryMib) + "MiB");
        }

        if (containsBareMetal(request) && !containsBareMetal(revised)) {
            reasons.add("missing_deployment_constraint:bare_metal");
        }

        if (!INTERNAL_PROCESS_DISCLOSURE.matcher(request).find()
                && INTERNAL_PROCESS_DISCLOSURE.matcher(revised).find()) {
            reasons.add("internal_process_disclosure");
        }

        checkMemoryBudget(revised, appCount, totalMemoryMib, reasons);
        checkArithmetic(revised, reasons);
        checkExclusiveGarbageCollectors(revised, reasons);
        return reasons.isEmpty() ? Decision.pass() : Decision.rejected(reasons);
    }

    public boolean hasDeterministicConstraints(Prompt original) {
        String request = latestUserText(original);
        return firstGroup(JAVA_VERSION, request) != null
                || firstGroup(SPRING_BOOT_VERSION, request) != null
                || firstNumber(APP_COUNT, request) != null
                || systemMemoryMib(request) != null
                || containsBareMetal(request);
    }

    /** Safe user-facing response when both the draft and reviewer violate known constraints. */
    public String safeFallback(Prompt original) {
        String request = latestUserText(original);
        Double count = firstNumber(APP_COUNT, request);
        Double memoryMib = systemMemoryMib(request);
        boolean thai = Pattern.compile("[ก-๙]").matcher(request).find();
        if (thai) {
            StringBuilder text = new StringBuilder("มินิคุงยังไม่ควรเสนอ JVM flags จากคำตอบนี้ครับ "
                    + "เพราะการตรวจความสอดคล้องพบว่าค่าที่คำนวณหรือข้อเท็จจริงยังไม่ผ่านข้อจำกัดของโจทย์");
            if (count != null && memoryMib != null && count > 0) {
                double safeFleetMib = memoryMib * maxMemoryUtilization;
                text.append("\n\nสำหรับ RAM ").append(display(memoryMib / 1024.0)).append(" GiB และ ")
                        .append(display(count)).append(" แอป เมื่อกัน RAM ไว้ ")
                        .append(display((1.0 - maxMemoryUtilization) * 100.0))
                        .append("% เพดานเฉลี่ยต่อ process ต้องไม่เกินประมาณ ")
                        .append(display(safeFleetMib / count)).append(" MiB โดยต้องรวม heap, metaspace, ")
                        .append("direct memory, code cache, thread stacks และ native memory ทั้งหมด ไม่ใช่เฉพาะ -Xmx");
            }
            return text.append("\n\nจึงยังไม่ควรนำค่าจากคำตอบก่อนหน้าไปใช้จริงจนกว่าจะวัด RSS/NMT ของแต่ละ service และคำนวณใหม่ครับ")
                    .toString();
        }
        StringBuilder text = new StringBuilder(
                "Mini-kun cannot safely recommend the JVM flags from this answer because its facts or calculations violate the request constraints.");
        if (count != null && memoryMib != null && count > 0) {
            double safeFleetMib = memoryMib * maxMemoryUtilization;
            text.append(" With ").append(display(memoryMib / 1024.0)).append(" GiB for ")
                    .append(display(count)).append(" applications, the average total process budget is at most about ")
                    .append(display(safeFleetMib / count))
                    .append(" MiB after reserve, including heap and all native memory.");
        }
        return text.append(" Do not use the earlier values until RSS/NMT measurements support a new calculation.").toString();
    }

    /** Facts repeated in the reviewer prompt reduce avoidable quality-gate rejections. */
    public String constraints(Prompt original) {
        String request = latestUserText(original);
        List<String> facts = new ArrayList<>();
        addFact(facts, "Java/JDK", firstGroup(JAVA_VERSION, request));
        addFact(facts, "Spring Boot", firstGroup(SPRING_BOOT_VERSION, request));
        Double count = firstNumber(APP_COUNT, request);
        if (count != null) facts.add("workload_count=" + display(count));
        Double memoryMib = systemMemoryMib(request);
        if (memoryMib != null) facts.add("total_memory=" + display(memoryMib) + " MiB");
        if (containsBareMetal(request)) facts.add("deployment=bare_metal");
        return facts.isEmpty() ? "none detected" : String.join(", ", facts);
    }

    private void requireVersion(String name, String expected, Pattern pattern, String revised,
            List<String> reasons) {
        if (expected == null) return;
        Set<String> values = allGroups(pattern, revised);
        if (!values.contains(expected)) {
            reasons.add("missing_or_changed_version:" + name + "=" + expected);
        }
    }

    private void checkMemoryBudget(String revised, Double appCount, Double totalMemoryMib,
            List<String> reasons) {
        if (appCount == null || totalMemoryMib == null || appCount <= 0 || totalMemoryMib <= 0) return;

        Map<String, Double> caps = new LinkedHashMap<>();
        Matcher flags = JVM_MEMORY_FLAG.matcher(revised);
        while (flags.find()) {
            String flag = flags.group(1).toLowerCase(Locale.ROOT);
            double mib = toMib(number(flags.group(2)), flags.group(3));
            caps.merge(flag, mib, Math::max);
        }
        Matcher perInstance = PER_INSTANCE_MEMORY.matcher(revised);
        double explicitPerInstanceMib = 0;
        while (perInstance.find()) {
            explicitPerInstanceMib = Math.max(explicitPerInstanceMib,
                    toMib(number(perInstance.group(1)), perInstance.group(2)));
        }

        double flagBudgetMib = caps.values().stream().mapToDouble(Double::doubleValue).sum();
        double knownPerInstanceMib = Math.max(flagBudgetMib, explicitPerInstanceMib);
        if (knownPerInstanceMib <= 0) return;

        double fleetMib = knownPerInstanceMib * appCount;
        double safeMib = totalMemoryMib * maxMemoryUtilization;
        if (fleetMib > safeMib + 0.5) {
            reasons.add("jvm_memory_budget_exceeded:per_instance=" + display(knownPerInstanceMib)
                    + "MiB,fleet=" + display(fleetMib) + "MiB,safe_limit=" + display(safeMib) + "MiB");
        }
    }

    private void checkArithmetic(String revised, List<String> reasons) {
        Matcher matcher = MEMORY_EQUATION.matcher(revised);
        while (matcher.find()) {
            double multiplier = number(matcher.group(1));
            double perUnitMib = toMib(number(matcher.group(2)), matcher.group(3));
            double statedMib = toMib(number(matcher.group(4)), matcher.group(5));
            double expectedMib = multiplier * perUnitMib;
            double scale = Math.max(Math.abs(expectedMib), 1.0);
            if (Math.abs(expectedMib - statedMib) / scale > arithmeticTolerance) {
                reasons.add("inconsistent_memory_equation:" + compact(matcher.group()));
            }
        }
    }

    private void checkExclusiveGarbageCollectors(String revised, List<String> reasons) {
        String normalized = revised.toLowerCase(Locale.ROOT);
        List<String> selected = EXCLUSIVE_GC_FLAGS.stream().filter(normalized::contains).toList();
        if (selected.size() > 1) {
            reasons.add("conflicting_garbage_collectors:" + String.join(",", selected));
        }
    }

    private boolean containsEquivalentMemory(String text, double expectedMib) {
        Matcher matcher = MEMORY_VALUE.matcher(text);
        while (matcher.find()) {
            double actualMib = toMib(number(matcher.group(1)), matcher.group(2));
            if (approximately(actualMib, expectedMib, 0.02)) return true;
        }
        return false;
    }

    private boolean containsEquivalentNumber(Pattern pattern, String text, double expected) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (approximately(number(matcher.group(1)), expected, 0.001)) return true;
        }
        return false;
    }

    private Double systemMemoryMib(String text) {
        Double value = memoryFrom(MEMORY_BEFORE_RAM, text);
        return value == null ? memoryFrom(RAM_BEFORE_MEMORY, text) : value;
    }

    private Double memoryFrom(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? toMib(number(matcher.group(1)), matcher.group(2)) : null;
    }

    private String latestUserText(Prompt prompt) {
        if (prompt == null) return "";
        return prompt.getInstructions().stream()
                .filter(message -> "user".equalsIgnoreCase(message.getMessageType().getValue()))
                .map(Message::getText)
                .reduce((left, right) -> right)
                .orElse("");
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private Double firstNumber(Pattern pattern, String text) {
        String value = firstGroup(pattern, text);
        return value == null ? null : number(value);
    }

    private Set<String> allGroups(Pattern pattern, String text) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text == null ? "" : text);
        while (matcher.find()) values.add(matcher.group(1));
        return values;
    }

    private boolean containsBareMetal(String text) {
        return text != null && Pattern.compile("(?iu)\\bbare[ -]?metal\\b|เครื่องจริง|ไม่ใช้คอนเทนเนอร์")
                .matcher(text).find();
    }

    private double toMib(double value, String unit) {
        String normalized = unit.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "k", "kb", "kib" -> value / 1024.0;
            case "m", "mb", "mib" -> value;
            case "g", "gb", "gib" -> value * 1024.0;
            case "t", "tb", "tib" -> value * 1024.0 * 1024.0;
            default -> value;
        };
    }

    private double number(String value) {
        return Double.parseDouble(value.replace(",", ""));
    }

    private boolean approximately(double left, double right, double tolerance) {
        return Math.abs(left - right) <= Math.max(Math.abs(right), 1.0) * tolerance;
    }

    private double bounded(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
    }

    private void addFact(List<String> facts, String name, String value) {
        if (value != null) facts.add(name + "=" + value);
    }

    private String display(double value) {
        return Math.rint(value) == value ? Long.toString(Math.round(value))
                : String.format(Locale.ROOT, "%.2f", value);
    }

    private String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    public record Decision(boolean accepted, List<String> reasons) {
        public Decision {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public static Decision pass() {
            return new Decision(true, List.of());
        }

        public static Decision rejected(List<String> reasons) {
            return new Decision(false, reasons);
        }

        public String summary() {
            return reasons.isEmpty() ? "accepted" : String.join(";", reasons);
        }
    }
}
