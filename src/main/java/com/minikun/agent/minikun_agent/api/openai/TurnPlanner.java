package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.agent.minikun_agent.conversation.ChatMessage;
import com.minikun.model.CooperationRouter;
import com.minikun.model.CooperationRoutingDecision;
import com.minikun.personality.companion.CompanionMode;
import com.minikun.personality.companion.CompanionModeContext;
import com.minikun.research.ResearchIntentDetector;
import com.minikun.tools.ToolEvidence;
import com.minikun.tools.ToolRequestRouter;
import com.minikun.visual.StoryIllustrationIntent;
import com.minikun.visual.StoryIllustrationIntentDetector;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.weather.DeviceLocation;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/** Produces the single routing plan consumed by retrieval, generation, tools, and review. */
@Component
@Slf4j
final class TurnPlanner {
    private static final Pattern CASUAL = Pattern.compile(
            "(?iu)^(?:สวัสดี|หวัดดี|ขอบคุณ|ขอบใจ|ฝันดี|hello|hi|hey|thanks|thank you)[!?.… ]*$");
    private static final Pattern AMBIGUOUS = Pattern.compile(
            "(?iu)^(?:เอา|เลือก|ทำ|ใช้|ต่อ|ลอง|จัดการ)?\\s*(?:อัน|เรื่อง|แบบ|ตัว)?(?:นั้น|นี้|เดิม|แรก|ต่อ)?"
                    + "(?:ให้หน่อย|เลย|ต่อ|ครับ|ค่ะ)?[!?.… ]*$|"
                    + "^(?:do|use|pick|choose|continue|that|this|same|the first one)(?:\\s+it)?[!?.… ]*$");
    private static final Pattern BACKGROUND = Pattern.compile(
            "(?iu)(ทำงานเบื้องหลัง|background\\s+(?:job|task)|วิจัยเชิงลึก|deep\\s*research)");
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "(?iu)(ต่อ|จากตรงนั้น|อันเดิม|แบบเดิม|เหมือนเดิม|continue|from there|same one)");
    private static final Pattern VISUAL_APPROVAL = Pattern.compile(
            "(?iu)^(?:เอาตามนี้|ทำตามนี้|ตามนี้|ตกลง|โอเค|ได้|ทำ|สร้าง|วาด|จัดมา)(?:เลย|ครับ|ค่ะ|นะ|ให้หน่อย|ได้เลย)*[!?.… ]*$");
    private static final Pattern VISUAL_REVISION = Pattern.compile(
            "(?iu)(ปรับ|เปลี่ยน|แก้|แปลง|(?:ลอง)?ทำ(?:ใหม่)?(?:ให้)?เป็น|(?:ภาพ|รูป|กราฟิก)(?:นี้|เดิม)|revise|change|convert|redraw)");
    private final CooperationRouter cooperationRouter;
    private final TurnAmbiguityResolver ambiguityResolver;
    private final StoryIllustrationIntentDetector illustrationIntentDetector;
    private final boolean autoIllustrateCreativeStories;
    private final List<ToolRequestRouter> toolRequestRouters;
    private final ToolRuntimeIntentDetector toolIntent = new ToolRuntimeIntentDetector();
    private final ResearchIntentDetector researchIntent = new ResearchIntentDetector();

    @Autowired
    TurnPlanner(
            CooperationRouter cooperationRouter,
            ObjectProvider<TurnAmbiguityResolver> ambiguityResolver,
            ObjectProvider<StoryIllustrationIntentDetector> illustrationIntentDetector,
            ObjectProvider<ToolRequestRouter> toolRequestRouters,
            @Value("${minikun.visual.generation.auto-illustrate-stories:false}") boolean autoIllustrateCreativeStories) {
        this(cooperationRouter,
                ambiguityResolver == null ? null : ambiguityResolver.getIfAvailable(),
                illustrationIntentDetector == null ? null : illustrationIntentDetector.getIfAvailable(),
                toolRequestRouters == null ? List.of() : toolRequestRouters.orderedStream().toList(),
                autoIllustrateCreativeStories);
    }

    TurnPlanner(
            CooperationRouter cooperationRouter,
            ObjectProvider<TurnAmbiguityResolver> ambiguityResolver,
            ObjectProvider<StoryIllustrationIntentDetector> illustrationIntentDetector,
            boolean autoIllustrateCreativeStories) {
        this(cooperationRouter,
                ambiguityResolver == null ? null : ambiguityResolver.getIfAvailable(),
                illustrationIntentDetector == null ? null : illustrationIntentDetector.getIfAvailable(),
                List.of(), autoIllustrateCreativeStories);
    }

    TurnPlanner(CooperationRouter cooperationRouter, TurnAmbiguityResolver ambiguityResolver) {
        this(cooperationRouter, ambiguityResolver, null, true);
    }

    TurnPlanner(
            CooperationRouter cooperationRouter,
            TurnAmbiguityResolver ambiguityResolver,
            StoryIllustrationIntentDetector illustrationIntentDetector,
            boolean autoIllustrateCreativeStories) {
        this(cooperationRouter, ambiguityResolver, illustrationIntentDetector, List.of(),
                autoIllustrateCreativeStories);
    }

    TurnPlanner(
            CooperationRouter cooperationRouter,
            TurnAmbiguityResolver ambiguityResolver,
            StoryIllustrationIntentDetector illustrationIntentDetector,
            List<ToolRequestRouter> toolRequestRouters,
            boolean autoIllustrateCreativeStories) {
        this.cooperationRouter = cooperationRouter == null ? new CooperationRouter() : cooperationRouter;
        this.ambiguityResolver = ambiguityResolver;
        this.illustrationIntentDetector = illustrationIntentDetector == null
                ? new StoryIllustrationIntentDetector(this.cooperationRouter)
                : illustrationIntentDetector;
        this.autoIllustrateCreativeStories = autoIllustrateCreativeStories;
        this.toolRequestRouters = toolRequestRouters == null ? List.of() : List.copyOf(toolRequestRouters);
    }

    /** Owns ordered deterministic routing so ChatService and planning use one route owner. */
    Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId,
            boolean toolsEnabled) {
        return route(userText, conversationId, ownerId, toolsEnabled, toolRequestRouters);
    }

    Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId,
            boolean toolsEnabled, List<ToolRequestRouter> fallbackRouters) {
        return route(userText, conversationId, ownerId, toolsEnabled, fallbackRouters, null);
    }

    Optional<ToolEvidence> route(String userText, ConversationId conversationId, String ownerId,
            boolean toolsEnabled, List<ToolRequestRouter> fallbackRouters, DeviceLocation deviceLocation) {
        List<ToolRequestRouter> routers = toolRequestRouters.isEmpty() && fallbackRouters != null
                && !fallbackRouters.isEmpty() ? fallbackRouters : toolRequestRouters;
        if (!toolsEnabled || routers.isEmpty()) return Optional.empty();
        for (ToolRequestRouter router : routers) {
            Optional<ToolEvidence> result = router.route(
                    userText == null ? "" : userText, conversationId, ownerId, deviceLocation);
            if (result.isPresent()) {
                ToolEvidence evidence = result.get();
                log.info("process=tool_route event=completed tool={} success={} route_owner=turn_planner",
                        evidence.toolName(), evidence.success());
                return result;
            }
        }
        return Optional.empty();
    }

    TurnPlan plan(String message, String conversationContext, CompanionModeContext mode,
            boolean hasVision, ToolEvidence verifiedTool, boolean toolsAvailable) {
        return plan(message, conversationContext, mode, hasVision, verifiedTool, toolsAvailable, false);
    }

    TurnPlan plan(String message, String conversationContext, CompanionModeContext mode,
            boolean hasVision, ToolEvidence verifiedTool, boolean toolsAvailable, boolean voiceMode) {
        return plan(message, conversationContext, mode, hasVision, verifiedTool, toolsAvailable, voiceMode, message);
    }

    TurnPlan plan(String message, String conversationContext, CompanionModeContext mode,
            boolean hasVision, ToolEvidence verifiedTool, boolean toolsAvailable, boolean voiceMode,
            String visualRequest) {
        String text = message == null ? "" : message.strip();
        CooperationRoutingDecision cooperation = cooperationRouter.decide(text);
        boolean contextualRoute = text.length() <= 160 && FOLLOW_UP.matcher(text).find()
                && conversationContext != null && !conversationContext.isBlank();
        if (contextualRoute && !cooperation.needsExpert()
                && !"creative_request".equals(cooperation.reason())) {
            CooperationRoutingDecision previous = cooperationRouter.decide(conversationContext);
            if (previous.needsExpert() || "creative_request".equals(previous.reason())) cooperation = previous;
        }
        boolean creative = "creative_request".equals(cooperation.reason());
        boolean imageOutput = !voiceMode && illustrationIntentDetector
                .detect(visualRequest, autoIllustrateCreativeStories) != StoryIllustrationIntent.NONE;
        boolean research = researchIntent.detect(text).deepResearch();
        boolean tools = toolsAvailable && verifiedTool == null && toolIntent.requiresTools(text);
        boolean ambiguous = text.length() <= 100 && AMBIGUOUS.matcher(text.toLowerCase(Locale.ROOT)).find()
                && conversationContext != null && !conversationContext.isBlank();
        TurnPlan.Intent intent = intent(mode, hasVision, tools, research, creative, cooperation);
        double confidence = ambiguous ? 0.45 : 0.9;
        String reason = intent == TurnPlan.Intent.TECHNICAL
                ? "technical:" + cooperation.reason() : reason(intent);
        if (contextualRoute) reason = "contextual_" + reason;
        TurnPlan.Execution execution = research || BACKGROUND.matcher(text).find()
                ? TurnPlan.Execution.BACKGROUND
                : tools ? TurnPlan.Execution.TOOL_LOOP : TurnPlan.Execution.DIRECT_STREAM;
        if (ambiguous && ambiguityResolver != null) {
            Optional<TurnAmbiguityResolver.Resolution> resolved = ambiguityResolver.resolve(text, conversationContext);
            if (resolved.isPresent()) {
                var value = resolved.get();
                intent = value.intent();
                tools = toolsAvailable && (tools || value.needsTools());
                execution = value.background() ? TurnPlan.Execution.BACKGROUND
                        : tools ? TurnPlan.Execution.TOOL_LOOP : TurnPlan.Execution.DIRECT_STREAM;
                confidence = value.confidence();
                reason = "ambiguity_resolved:" + value.reason();
            } else {
                reason = "ambiguous_deterministic_fallback";
            }
        }
        boolean simpleCasual = CASUAL.matcher(text).matches() && !ambiguous;
        if (simpleCasual && intent == TurnPlan.Intent.GENERAL) {
            intent = TurnPlan.Intent.COMPANION;
            reason = "casual_greeting";
        }
        TurnPlan plan = new TurnPlan(intent, execution, cooperation,
                !simpleCasual, !simpleCasual, false, tools, hasVision, research, creative,
                ambiguous, confidence, reason, imageOutput, routeSource(verifiedTool, tools));
        log.info("process=turn_plan event=completed intent={} execution={} tools={} image_output={} route_source={} "
                        + "ambiguous={} confidence={} reason={}",
                plan.intent(), plan.execution(), plan.needsTools(), plan.imageOutput(), plan.routeSource(),
                plan.ambiguous(), plan.confidence(), plan.reason());
        return plan;
    }

    String resolveVisualRequest(String message, List<ChatMessage> history) {
        if (message == null) return null;
        boolean approval = VISUAL_APPROVAL.matcher(message.strip()).matches();
        boolean revision = visualOutputRequested(message) && VISUAL_REVISION.matcher(message).find();
        if (!approval && !revision) return message;
        String request = null;
        var context = new java.util.ArrayList<ChatMessage>();
        for (ChatMessage previous : history.reversed()) {
            if ("assistant".equals(previous.role())) context.add(previous);
            if (!"user".equals(previous.role())) continue;
            if (visualOutputRequested(previous.content())) {
                if (request == null) request = approval ? previous.content() : message;
                context.add(previous);
                if (!VISUAL_REVISION.matcher(previous.content()).find()) break;
            } else if (VISUAL_APPROVAL.matcher(previous.content().strip()).matches()) {
                context.add(previous);
            } else {
                break;
            }
        }
        if (request == null) return message;
        return request + StoryIllustrationIntentDetector.PREVIOUS_GRAPHIC_CONTEXT
                + context.reversed().stream().map(previous -> previous.role() + ": " + previous.content())
                        .collect(java.util.stream.Collectors.joining("\n"));
    }

    boolean visualOutputRequested(String message) {
        return illustrationIntentDetector.detect(message, false) != StoryIllustrationIntent.NONE;
    }

    private String routeSource(ToolEvidence verifiedTool, boolean tools) {
        if (verifiedTool != null) {
            return verifiedTool.finalResponse() ? "deterministic_tool_final" : "deterministic_tool";
        }
        return tools ? "native_tool_loop" : "turn_planner";
    }

    private TurnPlan.Intent intent(CompanionModeContext mode, boolean vision, boolean tools,
            boolean research, boolean creative, CooperationRoutingDecision cooperation) {
        if (vision) return TurnPlan.Intent.VISION;
        if (research) return TurnPlan.Intent.RESEARCH;
        if (tools) return TurnPlan.Intent.ACTION;
        if (creative) return TurnPlan.Intent.CREATIVE;
        if (cooperation.needsExpert()) return TurnPlan.Intent.TECHNICAL;
        if (mode != null && mode.mode() == CompanionMode.COMPANION) return TurnPlan.Intent.COMPANION;
        if (mode != null && (mode.mode() == CompanionMode.WORK || mode.mode() == CompanionMode.FOCUS)) {
            return TurnPlan.Intent.WORK;
        }
        return TurnPlan.Intent.GENERAL;
    }

    private String reason(TurnPlan.Intent intent) {
        return switch (intent) {
            case ACTION -> "tool_action";
            case RESEARCH -> "deep_research";
            case CREATIVE -> "creative_request";
            case TECHNICAL -> "technical_request";
            case VISION -> "vision_input";
            case COMPANION -> "companion_mode";
            case WORK -> "work_mode";
            default -> "deterministic_default";
        };
    }
}
