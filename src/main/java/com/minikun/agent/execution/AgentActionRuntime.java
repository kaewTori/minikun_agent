package com.minikun.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.computer.LocalComputerService;
import com.minikun.tools.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import jakarta.annotation.PreDestroy;

/** One bounded worker over the existing run/step ledger. No model-owned approval or write replay. */
public class AgentActionRuntime {
    private static final Set<String> TOOLS = Set.of("homelab.guardian", "system.health", "service.health",
            "computer.local", "browser.control", "task.manage", "planner.manage", "calendar.manage",
            "communication.assist", "web.search", "web.open_url", "calculator.add", "time.get_current_time");
    private final AgentActionStore store;
    private final AgentExecutionService executions;
    private final ObjectProvider<ToolExecutor> executor;
    private final ObjectProvider<ToolRegistry> registry;
    private final ObjectProvider<LocalComputerService> computer;
    private final ObjectMapper mapper;
    private final Clock clock;
    private com.minikun.guardian.GuardianActionService guardianActions;
    private com.minikun.notification.NotificationDispatcher notifications;
    private com.minikun.systemhealth.SystemHealthReader healthReader;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGuardianActions(com.minikun.guardian.GuardianActionService guardianActions) { this.guardianActions = guardianActions; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNotifications(com.minikun.notification.NotificationDispatcher notifications) { this.notifications = notifications; }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHealthReader(com.minikun.systemhealth.SystemHealthReader healthReader) { this.healthReader = healthReader; }
    // ponytail: one server/one action worker; add a database lease before deploying multiple replicas.
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(20), Thread.ofPlatform().daemon().name("minikun-action").factory(), new ThreadPoolExecutor.AbortPolicy());
    private final Set<UUID> queued = ConcurrentHashMap.newKeySet();

    public AgentActionRuntime(AgentActionStore store, AgentExecutionService executions,
            ObjectProvider<ToolExecutor> executor, ObjectProvider<ToolRegistry> registry,
            ObjectProvider<LocalComputerService> computer, ObjectMapper mapper, Clock clock) {
        this.store = store; this.executions = executions; this.executor = executor; this.registry = registry;
        this.computer = computer; this.mapper = mapper; this.clock = clock;
    }

    @Transactional
    public AgentActionCheckpoint start(String owner, String conversation, String key, AgentActionPlan plan) {
        var existing = store.byKey(owner, key);
        if (existing.isPresent()) {
            if (!existing.get().plan().equals(plan)) throw new IllegalArgumentException("idempotency key belongs to a different plan");
            return existing.get();
        }
        validate(plan);
        AgentRun run = executions.startAction(owner, conversation, new AgentPlanDraft(plan.objective(),
                plan.steps().stream().map(AgentActionPlan.Step::title).toList(),
                new AgentRiskAssessment(AgentRiskLevel.MEDIUM, List.of("observable action plan")))).orElseThrow();
        var state = new AgentActionCheckpoint(run.id(), plan, 0, "READY", Map.of(), "", null,
                clock.instant().plusSeconds(plan.timeoutSeconds()), Map.of(), 0);
        store.create(owner, key, state);
        return state;
    }

    public void validate(AgentActionPlan plan) {
        for (var step : plan.steps()) {
            Tool tool = tool(step.tool());
            if (step.arguments().containsKey("password") || step.arguments().containsKey("token")) throw new IllegalArgumentException("credentials must stay in server configuration");
            if ("computer.local".equals(step.tool()) && "clipboard".equals(step.arguments().get("action"))) throw new IllegalArgumentException("clipboard is available only on explicit direct requests");
            if (tool.requiresExplicitConfirmation(step.arguments()) && !intrinsic(step) && step.verify() == null)
                throw new IllegalArgumentException("this action requires an independent read-back check");
            validateCheck(step.verify()); validateCheck(step.skipIf());
            if (step.skipIf() != null && !step.skipIf().equals(step.verify())) throw new IllegalArgumentException("skipIf must check the same requested outcome as verify");
        }
    }

    private void validateCheck(AgentActionPlan.Check check) {
        if (check != null && tool(check.tool()).requiresExplicitConfirmation(check.arguments())) throw new IllegalArgumentException("verification must be read-only");
    }
    private Tool tool(String name) {
        if (!TOOLS.contains(name)) throw new IllegalArgumentException("tool is outside the action runtime allowlist");
        return registry.getObject().find(name).orElseThrow(() -> new IllegalArgumentException("tool unavailable: " + name));
    }

    public AgentActionCheckpoint find(String owner, UUID id) {
        executions.find(owner, id);
        return store.find(owner, id).orElseThrow(() -> new IllegalArgumentException("action run not found"));
    }

    public boolean hasAction(String owner, UUID id) { return store.find(owner, id).isPresent(); }
    public Map<String, Object> catalog() {
        var local = computer.getIfAvailable();
        return Map.of("tools", registry.getObject().definitions().stream().filter(d -> TOOLS.contains(d.name())).map(ToolDefinition::name).toList(),
                "roots", local == null ? List.of() : local.roots(), "workflows", local == null ? List.of() : local.workflows(),
                "guardianActions", guardianActions == null ? List.of() : guardianActions.available(),
                "components", healthReader == null ? List.of() : healthReader.read().dependencies().keySet().stream().sorted().toList());
    }
    public Object browserSnapshot(String owner, String url) {
        String id = "browser-inspect-" + UUID.randomUUID();
        var result = executor.getObject().execute(new ToolCallContext(new ConversationId("cockpit-browser"), id, owner),
                new ToolCall(id, "browser.control", Map.of("action", "snapshot", "url", url)));
        if (!result.success()) throw new IllegalArgumentException(result.error());
        return result.value();
    }
    public static AgentActionPlan repairPlan(String component, String actionId) {
        if (component == null || !component.matches("[a-zA-Z0-9_-]{1,100}") || actionId == null || actionId.isBlank()) throw new IllegalArgumentException("component and configured action_id required");
        var up = new AgentActionPlan.Check("system.health", Map.of(), "/dependencies/" + component + "/status", "UP");
        return new AgentActionPlan("ตรวจและดูแลบริการ " + component, List.of(
                new AgentActionPlan.Step("ตรวจสถานะและหลักฐาน", "homelab.guardian", Map.of("action", "inspect"), null, null),
                new AgentActionPlan.Step("แก้บริการ " + component + " ด้วย " + actionId, "homelab.guardian", Map.of("action", "execute", "action_id", actionId), up, up)), 600);
    }
    public AgentActionCheckpoint review(String owner, UUID id) {
        var state = find(owner, id);
        var status = executions.find(owner, id).status();
        if (!Set.of(AgentRunStatus.REVIEW_REQUIRED, AgentRunStatus.UNVERIFIED).contains(status)) throw new IllegalArgumentException("run does not need outcome review");
        var step = state.plan().steps().get(state.nextStep());
        // Review only reads current state; it never re-executes the action.
        state = store.save(owner, new AgentActionCheckpoint(state.runId(), state.plan(), state.nextStep(), "REVIEWING", state.prepared(), state.digest(), null,
                clock.instant().plusSeconds(60), state.evidence(), state.revision()));
        executions.actionStatus(owner, id, AgentRunStatus.RUNNING, state.nextStep(), "กำลังตรวจผลจริง ไม่มีการสั่งงานซ้ำ");
        if (verifiableWithoutResult(step) && verify(owner, state, step, null)) next(owner, state, "ยืนยันจากสถานะปัจจุบันแล้ว");
        else stop(owner, state, AgentRunStatus.REVIEW_REQUIRED, "ยังยืนยันผลไม่ได้ โปรดตรวจและเริ่มแผนใหม่เมื่อพร้อม");
        return find(owner, id);
    }

    public record View(AgentRun run, AgentActionCheckpoint checkpoint, List<AgentExecutionStep> steps, Map<String, String> permission) { }
    public View view(String owner, UUID id) {
        var checkpoint = find(owner, id);
        Map<String, String> permission = Map.of();
        if (checkpoint.nextStep() < checkpoint.plan().steps().size()) {
            var step = checkpoint.plan().steps().get(checkpoint.nextStep());
            permission = Map.of("tool", step.tool(), "action", text(step.arguments(), "action"), "resource", resource(step.tool(), checkpoint.prepared().isEmpty() ? step.arguments() : checkpoint.prepared()));
        }
        return new View(executions.find(owner, id), checkpoint, executions.steps(owner, id), permission);
    }

    public AgentActionCheckpoint decide(String owner, UUID id, String digest, boolean approve) {
        var state = find(owner, id);
        if (!"WAITING".equals(state.phase()) || !Objects.equals(state.digest(), digest)) throw new IllegalArgumentException("preview changed; refresh before approving");
        if (!approve) return cancel(owner, id);
        if (state.consentExpiresAt() == null || !clock.instant().isBefore(state.consentExpiresAt())) throw new IllegalArgumentException("preview expired; prepare a new plan");
        var updated = store.save(owner, state.change("AUTHORIZED", state.nextStep(), state.prepared(), digest, state.consentExpiresAt(), state.evidence()));
        executions.actionStatus(owner, id, AgentRunStatus.PLANNED, state.nextStep(), "อนุมัติขั้นที่เตรียมไว้แล้ว กำลังทำต่อ");
        return updated;
    }

    public AgentActionCheckpoint cancel(String owner, UUID id) {
        var state = find(owner, id);
        if (executions.find(owner, id).status().terminal()) throw new IllegalArgumentException("run already stopped");
        var updated = store.save(owner, state.change("CANCELLED", state.nextStep(), state.prepared(), "", null, state.evidence()));
        executions.actionStatus(owner, id, AgentRunStatus.CANCELLED, state.nextStep(), "หยุดงานแล้ว คำสั่งที่เริ่มไปอาจยังทำงานอยู่ โปรดตรวจหลักฐานล่าสุด");
        return updated;
    }

    public List<AgentActionStore.Grant> grants(String owner) { return store.grants(owner); }
    public AgentActionStore.Grant grant(String owner, String toolName, String action, String resource,
            Instant expiry, int maxUses, int cooldown, String monitor) {
        if (!Set.of("homelab.guardian", "computer.local", "browser.control", "task.manage", "planner.manage").contains(toolName)) throw new IllegalArgumentException("unsupported grant tool");
        if (expiry == null || !expiry.isAfter(clock.instant()) || expiry.isAfter(clock.instant().plusSeconds(30 * 86400))) throw new IllegalArgumentException("grant expiry must be within 30 days");
        if (monitor != null && !monitor.isBlank() && (!toolName.equals("homelab.guardian") || !action.equals("execute") || !monitor.matches("[a-zA-Z0-9_-]{1,100}"))) throw new IllegalArgumentException("monitor requires a named guardian component");
        tool(toolName);
        return store.grant(owner, toolName, action, resource, expiry, maxUses, cooldown, monitor);
    }
    public void revoke(String owner, UUID id) { store.revoke(owner, id, clock.instant()); }

    @Scheduled(fixedDelayString = "${minikun.agent.action.poll-interval-ms:1000}")
    public void poll() {
        for (var state : store.active()) {
            if (!queued.add(state.runId())) continue;
            try { worker.execute(() -> {
                try { advance(executions.findRunOwner(state.runId()), state.runId()); }
                catch (RuntimeException exception) { /* durable state is inspected on the next poll */ }
                finally { queued.remove(state.runId()); }
            }); } catch (RejectedExecutionException exception) { queued.remove(state.runId()); }
        }
    }

    /** Public for deterministic tests; production callers submit through start/decide. */
    public void advance(String owner, UUID id) {
        var state = find(owner, id);
        if (Set.of("CANCELLED", "COMPLETED", "FAILED", "REVIEW_REQUIRED", "UNVERIFIED", "LIMIT_REACHED").contains(state.phase())) {
            if (!executions.find(owner, id).status().terminal()) executions.actionStatus(owner, id, AgentRunStatus.valueOf(state.phase()), state.nextStep(), "กู้คืนสถานะจาก checkpoint แล้ว");
            return;
        }
        if (executions.find(owner, id).status().terminal() || !Set.of("READY", "PREPARED", "AUTHORIZED", "EXECUTING", "VERIFYING", "REVIEWING").contains(state.phase())) return;
        try {
            if (!clock.instant().isBefore(state.deadline())) { stop(owner, state, AgentRunStatus.LIMIT_REACHED, "ถึงเวลาที่กำหนดแล้ว"); return; }
            if (state.nextStep() == state.plan().steps().size()) { stop(owner, state, AgentRunStatus.COMPLETED, "ตรวจผลครบทุกขั้นของแผนแล้ว"); return; }
            var step = state.plan().steps().get(state.nextStep());
            boolean write = tool(step.tool()).requiresExplicitConfirmation(step.arguments());
            if ("EXECUTING".equals(state.phase()) || "VERIFYING".equals(state.phase()) || "REVIEWING".equals(state.phase())) {
                // The prior process may have applied a write before losing its response.
                if (write && !verifiableWithoutResult(step)) { stop(owner, state, AgentRunStatus.REVIEW_REQUIRED, "ผลคำสั่งก่อนหน้าขาดหาย ต้องตรวจผลก่อนเริ่มงานใหม่"); return; }
                if (verify(owner, state, step, null)) { next(owner, state, "recovered by reading current state"); return; }
                if (write || "REVIEWING".equals(state.phase())) { stop(owner, state, AgentRunStatus.REVIEW_REQUIRED, "ยังยืนยันผลคำสั่งก่อนหน้าไม่ได้ ไม่มีการสั่งซ้ำ"); return; }
            }
            if ("READY".equals(state.phase()) && step.skipIf() != null && check(owner, state, step.skipIf())) { next(owner, state, "ข้ามคำสั่ง: เป้าหมายอยู่ในสถานะที่ต้องการแล้ว"); return; }
            if ("READY".equals(state.phase())) {
                Map<String, Object> prepared = prepare(step);
                String digest = digest(state.runId() + ":" + state.nextStep() + ":" + step.tool(), prepared);
                state = store.save(owner, state.change("PREPARED", state.nextStep(), prepared, digest, null, state.evidence()));
            }
            if (write && !"AUTHORIZED".equals(state.phase())) {
                boolean granted = store.consume(owner, step.tool(), text(step.arguments(), "action"), resource(step.tool(), state.prepared()), clock.instant());
                state = store.save(owner, state.change(granted ? "AUTHORIZED" : "WAITING", state.nextStep(), state.prepared(), state.digest(), clock.instant().plusSeconds(1800), state.evidence()));
                if (!granted) { executions.actionStatus(owner, id, AgentRunStatus.WAITING_CONFIRMATION, state.nextStep(), "รอยืนยัน: " + step.title()); notify(state, "WAITING", "รอยืนยัน: " + step.title()); return; }
            }
            if (write && !clock.instant().isBefore(state.consentExpiresAt())) { stop(owner, state, AgentRunStatus.REVIEW_REQUIRED, "การอนุมัติหมดอายุ ยังไม่ได้เริ่มคำสั่ง"); return; }
            if (!active(owner, id)) return;
            state = store.save(owner, state.change("EXECUTING", state.nextStep(), state.prepared(), state.digest(), null, state.evidence()));
            executions.actionStatus(owner, id, AgentRunStatus.RUNNING, state.nextStep(), step.title());
            ToolResult result = invoke(owner, state, step.tool(), state.prepared(), write);
            if (!active(owner, id)) return;
            if (!result.success() || mapper.valueToTree(result.value()).path("requires_confirmation").asBoolean(false)) {
                stop(owner, state, write ? AgentRunStatus.REVIEW_REQUIRED : AgentRunStatus.FAILED, "คำสั่งไม่สำเร็จหรือยังไม่ได้รับสิทธิ์ ไม่มีการสั่งเขียนซ้ำ"); return;
            }
            state = store.save(owner, state.change("VERIFYING", state.nextStep(), state.prepared(), state.digest(), null, state.evidence()));
            if (!verify(owner, state, step, result)) { stop(owner, state, AgentRunStatus.UNVERIFIED, "ลงมือแล้ว แต่ผลยังไม่ตรงเกณฑ์: " + step.title()); return; }
            next(owner, state, "ตรวจผลแล้ว: " + step.title());
        } catch (RuntimeException exception) {
            if (active(owner, id)) {
                var latest = find(owner, id);
                stop(owner, latest, Set.of("EXECUTING", "VERIFYING").contains(latest.phase()) ? AgentRunStatus.REVIEW_REQUIRED : AgentRunStatus.FAILED,
                        "งานหยุดเพราะขั้นตอนใช้ไม่ได้ โปรดตรวจหลักฐานล่าสุด");
            }
        }
    }

    private Map<String, Object> prepare(AgentActionPlan.Step step) {
        if ("browser.control".equals(step.tool()) && tool(step.tool()).requiresExplicitConfirmation(step.arguments()))
            return ((com.minikun.browser.BrowserControlTool) tool(step.tool())).prepare(step.arguments());
        if ("computer.local".equals(step.tool()) && tool(step.tool()).requiresExplicitConfirmation(step.arguments()))
            return computer.getObject().preview(step.arguments()).executionArguments();
        return step.arguments();
    }
    private boolean active(String owner, UUID id) { return !executions.find(owner, id).status().terminal() && !"CANCELLED".equals(find(owner, id).phase()); }
    private void next(String owner, AgentActionCheckpoint state, String evidence) {
        var ledger = new LinkedHashMap<>(state.evidence());
        ledger.put(Integer.toString(state.nextStep()), Map.of("result", evidence, "checkedAt", clock.instant().toString()));
        boolean completed = state.nextStep() + 1 == state.plan().steps().size();
        var updated = store.save(owner, state.change(completed ? "COMPLETED" : "READY", state.nextStep() + 1, Map.of(), "", null, ledger));
        executions.actionStatus(owner, state.runId(), updated.nextStep() == state.plan().steps().size() ? AgentRunStatus.COMPLETED : AgentRunStatus.PLANNED, updated.nextStep(), evidence);
        if (updated.nextStep() == state.plan().steps().size()) notify(updated, "COMPLETED", "ตรวจผลครบทุกขั้นแล้ว: " + state.plan().objective());
    }
    private void stop(String owner, AgentActionCheckpoint state, AgentRunStatus status, String reason) {
        store.save(owner, state.change(status.name(), state.nextStep(), state.prepared(), state.digest(), null, state.evidence()));
        executions.actionStatus(owner, state.runId(), status, state.nextStep(), reason);
        notify(state, status.name(), reason);
    }

    private void notify(AgentActionCheckpoint state, String event, String message) {
        if (notifications == null) return;
        try { notifications.publish(new com.minikun.notification.NotificationRequest("AGENT_ACTION", state.runId() + ":" + state.nextStep() + ":" + event,
                com.minikun.notification.NotificationChannel.REMINDER, "งานของมินิคุง", message, 3, "robot")); }
        catch (RuntimeException ignored) { /* outcome is durable even when delivery is unavailable */ }
    }

    @Scheduled(fixedDelayString = "${minikun.agent.action.monitor-interval-ms:60000}")
    public void monitor() {
        for (var grant : store.monitors(clock.instant())) {
            if (!queued.add(grant.id())) continue;
            try { worker.execute(() -> {
                try {
                    var context = new ToolCallContext(new ConversationId("agent-monitor"), "monitor-" + grant.id(), grant.ownerId());
                    var result = executor.getObject().execute(context, new ToolCall(context.callId(), "system.health", Map.of()));
                    var status = mapper.valueToTree(result.value()).at("/dependencies/" + grant.monitorComponent() + "/status");
                    if (!result.success() || !"DOWN".equals(status.asText())) return;
                    long bucket = clock.instant().getEpochSecond() / Math.max(60, grant.cooldownSeconds());
                    start(grant.ownerId(), "agent-monitor", "monitor:" + grant.id() + ":" + bucket, repairPlan(grant.monitorComponent(), grant.resource()));
                } catch (RuntimeException ignored) { /* no invented action when probes/configuration are unavailable */ }
                finally { queued.remove(grant.id()); }
            }); } catch (RejectedExecutionException exception) { queued.remove(grant.id()); }
        }
    }
    private ToolResult invoke(String owner, AgentActionCheckpoint state, String name, Map<String, Object> args, boolean authorized) {
        String callId = "action-" + state.nextStep() + "-" + UUID.randomUUID();
        var run = executions.find(owner, state.runId());
        var context = new ToolCallContext(new ConversationId(run.conversationId()), callId, owner);
        var call = new ToolCall(callId, name, args);
        executions.beginStep(state.runId(), callId, name, args);
        ToolResult result;
        try (var scope = new BackgroundToolScope(state.runId(), false, () -> active(owner, state.runId()) && clock.instant().isBefore(state.deadline()))) {
            result = authorized ? executor.getObject().executeAuthorized(context, call) : executor.getObject().execute(context, call);
        }
        executions.finishStep(state.runId(), callId, result, false);
        return result;
    }
    private boolean check(String owner, AgentActionCheckpoint state, AgentActionPlan.Check check) {
        validateCheck(check);
        var result = invoke(owner, state, check.tool(), check.arguments(), false);
        return result.success() && mapper.valueToTree(result.value()).at(check.pointer()).equals(mapper.valueToTree(check.expected()));
    }
    private boolean verify(String owner, AgentActionCheckpoint state, AgentActionPlan.Step step, ToolResult result) {
        if (step.verify() != null) {
            // Bounded probing only, never a second action. No sleeps on the scheduler thread.
            for (int attempt = 0; attempt < 3 && active(owner, state.runId()) && clock.instant().isBefore(state.deadline()); attempt++) {
                if (check(owner, state, step.verify())) return true;
                if (attempt < 2) try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            return false;
        }
        if ("computer.local".equals(step.tool())) {
            String action = text(step.arguments(), "action");
            var args = step.arguments();
            if (Set.of("write", "move").contains(action)) {
                String target = text(args, action.equals("move") ? "target" : "path");
                String hash = action.equals("move") ? text(state.prepared(), "expected_sha256") : digestBytes(text(args, "content").getBytes(StandardCharsets.UTF_8));
                var file = computer.getObject().stat(text(args, "root"), target);
                return hash.equals(file.get("sha256")) && (!action.equals("move") || Boolean.FALSE.equals(computer.getObject().stat(text(args, "root"), text(args, "path")).get("exists")));
            }
            if (action.equals("trash")) return result != null && Boolean.FALSE.equals(computer.getObject().stat(text(args, "root"), text(args, "path")).get("exists"))
                    && mapper.valueToTree(result.value()).path("recoverable").asBoolean(false);
        }
        return result != null && result.success() && (!tool(step.tool()).requiresExplicitConfirmation(step.arguments())
                || Set.of("task.manage", "planner.manage").contains(step.tool()));
    }
    private boolean intrinsic(AgentActionPlan.Step step) {
        return "computer.local".equals(step.tool()) && Set.of("write", "move", "trash").contains(text(step.arguments(), "action"))
                || Set.of("task.manage", "planner.manage").contains(step.tool());
    }
    private boolean verifiableWithoutResult(AgentActionPlan.Step step) {
        return step.verify() != null || "computer.local".equals(step.tool()) && Set.of("write", "move").contains(text(step.arguments(), "action"));
    }

    public static String resource(String tool, Map<String, Object> args) {
        return switch (tool) {
            case "homelab.guardian" -> text(args, "action_id");
            case "computer.local" -> "workflow".equals(text(args, "action")) ? text(args, "workflow_id")
                    : "open_app".equals(text(args, "action")) ? text(args, "application")
                    : text(args, "root") + ":" + text(args, "path") + (args.containsKey("target") ? "->" + text(args, "target") : "");
            case "browser.control" -> text(args, "url") + "#" + text(args, "selector");
            case "task.manage" -> text(args, "task_id").isBlank() ? text(args, "title") : text(args, "task_id");
            case "planner.manage" -> text(args, "reminder_id").isBlank() ? text(args, "title") : text(args, "reminder_id");
            default -> "";
        };
    }
    private String digest(String identity, Map<String, Object> args) {
        try { return digestBytes((identity + mapper.writeValueAsString(new TreeMap<>(args))).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalArgumentException("cannot fingerprint action", e); }
    }
    private static String digestBytes(byte[] value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String text(Map<String, Object> args, String key) { return Objects.toString(args.get(key), ""); }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
