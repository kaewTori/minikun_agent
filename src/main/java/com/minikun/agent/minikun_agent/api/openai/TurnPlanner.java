package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.model.CooperationRouter;
import com.minikun.model.CooperationRoutingDecision;
import com.minikun.personality.companion.CompanionMode;
import com.minikun.personality.companion.CompanionModeContext;
import com.minikun.research.ResearchIntentDetector;
import com.minikun.tools.ToolEvidence;
import com.minikun.visual.StoryIllustrationIntent;
import com.minikun.visual.StoryIllustrationIntentDetector;
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
    private final CooperationRouter cooperationRouter;
    private final TurnAmbiguityResolver ambiguityResolver;
    private final StoryIllustrationIntentDetector illustrationIntentDetector;
    private final boolean autoIllustrateCreativeStories;
    private final ToolRuntimeIntentDetector toolIntent = new ToolRuntimeIntentDetector();
    private final ResearchIntentDetector researchIntent = new ResearchIntentDetector();

    @Autowired
    TurnPlanner(
            CooperationRouter cooperationRouter,
            ObjectProvider<TurnAmbiguityResolver> ambiguityResolver,
            ObjectProvider<StoryIllustrationIntentDetector> illustrationIntentDetector,
            @Value("${minikun.visual.generation.auto-illustrate-stories:true}") boolean autoIllustrateCreativeStories) {
        this(cooperationRouter,
                ambiguityResolver == null ? null : ambiguityResolver.getIfAvailable(),
                illustrationIntentDetector == null ? null : illustrationIntentDetector.getIfAvailable(),
                autoIllustrateCreativeStories);
    }

    TurnPlanner(CooperationRouter cooperationRouter, TurnAmbiguityResolver ambiguityResolver) {
        this(cooperationRouter, ambiguityResolver, null, true);
    }

    TurnPlanner(
            CooperationRouter cooperationRouter,
            TurnAmbiguityResolver ambiguityResolver,
            StoryIllustrationIntentDetector illustrationIntentDetector,
            boolean autoIllustrateCreativeStories) {
        this.cooperationRouter = cooperationRouter == null ? new CooperationRouter() : cooperationRouter;
        this.ambiguityResolver = ambiguityResolver;
        this.illustrationIntentDetector = illustrationIntentDetector == null
                ? new StoryIllustrationIntentDetector(this.cooperationRouter)
                : illustrationIntentDetector;
        this.autoIllustrateCreativeStories = autoIllustrateCreativeStories;
    }

    TurnPlan plan(String message, String conversationContext, CompanionModeContext mode,
            boolean hasVision, ToolEvidence verifiedTool, boolean toolsAvailable) {
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
        boolean imageOutput = illustrationIntentDetector
                .detect(text, autoIllustrateCreativeStories) != StoryIllustrationIntent.NONE;
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
