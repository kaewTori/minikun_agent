package com.minikun.browser;

import com.minikun.tools.*;
import com.minikun.planner.PlannerConfirmationService;
import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;

/** Fresh DOM snapshots and explicit, fingerprinted interactions in the owner's browser profile. */
public final class BrowserControlTool implements Tool {
    private final BrowserSessionClient session;
    private final ObjectProvider<PlannerConfirmationService> confirmations;
    private final BrowserUrlPolicy urls = new BrowserUrlPolicy(true);
    private final String owner;
    public BrowserControlTool(BrowserSessionClient session, ObjectProvider<PlannerConfirmationService> confirmations,
            @Value("${minikun.sync.owner-id:default}") String owner) { this.session = session; this.confirmations = confirmations; this.owner = owner; }
    @Override public ToolDefinition definition() {
        return new ToolDefinition("browser.control", "Inspect and interact with the owner's browser session. Open a public URL, snapshot visible controls, then click/fill/select one exact selector. "
                + "Mutations need user consent and a fresh expected_state from snapshot. Never submit a form without approval. Passwords stay in the manual login browser.", Map.of(
                "action", new ToolParameter("action", ToolParameterType.STRING, true, "open, snapshot, click, fill, select"),
                "url", new ToolParameter("url", ToolParameterType.STRING, true, "Public HTTPS/HTTP URL"),
                "selector", new ToolParameter("selector", ToolParameterType.STRING, false, "Unique CSS selector from current snapshot"),
                "value", new ToolParameter("value", ToolParameterType.STRING, false, "Text for fill or value for select"),
                "expected_state", new ToolParameter("expected_state", ToolParameterType.STRING, false, "Server snapshot fingerprint; reserved"),
                "confirmed", new ToolParameter("confirmed", ToolParameterType.BOOLEAN, false, "Server-owned consent")));
    }
    @Override public boolean requiresExplicitConfirmation(Map<String, Object> args) { return !"snapshot".equals(args.get("action")); }
    public Map<String, Object> prepare(Map<String, Object> args) {
        urls.validate(URI.create(String.valueOf(args.get("url"))));
        var prepared = new LinkedHashMap<>(args);
        if (!"open".equals(args.get("action"))) {
            var snapshot = session.control("snapshot", String.valueOf(args.get("url")), Map.of("selector", Objects.toString(args.get("selector"), "")));
            prepared.put("expected_state", snapshot.path("state").asText());
            prepared.put("url", snapshot.path("url").asText(String.valueOf(args.get("url"))));
        }
        return prepared;
    }
    @Override public ToolResult execute(ToolCallContext context, Map<String, Object> args) {
        try {
            if (!owner.equals(context.ownerId())) return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, "browser profile belongs to another owner");
            String url = String.valueOf(args.get("url"));
            urls.validate(URI.create(url));
            String action = String.valueOf(args.get("action"));
            if (!Set.of("open", "snapshot", "click", "fill", "select").contains(action)) throw new IllegalArgumentException("unsupported browser action");
            if (requiresExplicitConfirmation(args) && !ToolAuthorizationScope.permits(context, definition().name(), args)) {
                var pending = confirmations.getIfAvailable();
                if (pending == null) throw new IllegalStateException("confirmation storage unavailable");
                var prepared = prepare(args);
                pending.save(context.conversationId(), context.ownerId(), "browser.execute", prepared);
                return ToolResult.success(Map.of("requires_confirmation", true, "proposed", prepared,
                        "message", "เตรียม " + action + " บน " + URI.create(url).getHost() + " แล้ว ยืนยันให้ดำเนินการไหมครับ"));
            }
            if (action.equals("open")) { session.open(url); return ToolResult.success(Map.of("opened", true, "url", url)); }
            var result = session.control(action, url, args);
            if (!result.path("success").asBoolean(false)) return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "browser action did not complete");
            return ToolResult.success(result);
        } catch (RuntimeException e) { return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "browser state changed or action is unavailable; inspect the page before retrying"); }
    }
}
