package com.minikun.agent.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.computer.*;
import com.minikun.guardian.*;
import com.minikun.tools.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

class AgentActionRuntimeTest {
    @TempDir Path root;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
    final AtomicBoolean healthy = new AtomicBoolean();
    final AtomicInteger writes = new AtomicInteger();
    final Map<UUID, AgentActionCheckpoint> checkpoints = new HashMap<>();
    AgentActionStore store;
    AgentExecutionService executions;
    AgentActionRuntime runtime;
    DefaultToolExecutor executor;
    Clock clock;
    LocalComputerService files;
    @BeforeEach void setup() {
        clock = mock(Clock.class); when(clock.instant()).thenAnswer(i -> now.get()); when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        var mapper = new ObjectMapper().findAndRegisterModules();
        executions = new AgentExecutionService(new InMemoryAgentExecutionStore(), mapper, clock, 1, 12);
        store = mock(AgentActionStore.class);
        when(store.find(anyString(), any())).thenAnswer(i -> Optional.ofNullable(checkpoints.get(i.getArgument(1))));
        when(store.byKey(anyString(), any())).thenReturn(Optional.empty());
        doAnswer(i -> { AgentActionCheckpoint s = i.getArgument(2); checkpoints.put(s.runId(),s); return null; }).when(store).create(anyString(), any(), any());
        when(store.save(anyString(), any())).thenAnswer(i -> {
            AgentActionCheckpoint s=i.getArgument(1), old=checkpoints.get(s.runId());
            if (s.revision()!=old.revision()) throw new IllegalStateException("stale checkpoint");
            var next=s.revised(s.revision()+1); checkpoints.put(s.runId(),next); return next;
        });
        files=new LocalComputerService(List.of(new ComputerRoot("work",root)),mock(ComputerAuditStore.class),
                new ComputerCommandGateway((argv,timeout)->new GuardianCommandResult(0,false,"test"),Duration.ofSeconds(1),List.of(),Set.of()),clock,65536,65536,6,1000);
        var guardian=new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("homelab.guardian","test",Map.of(
                "action",new ToolParameter("action",ToolParameterType.STRING,true,"test"),
                "action_id",new ToolParameter("action_id",ToolParameterType.STRING,false,"test"),
                "confirmed",new ToolParameter("confirmed",ToolParameterType.BOOLEAN,false,"test"))); }
            public boolean requiresExplicitConfirmation(Map<String,Object> a) { return "execute".equals(a.get("action")); }
            public ToolResult execute(ToolCallContext c,Map<String,Object> a) {
                if (!requiresExplicitConfirmation(a)) return ToolResult.success(Map.of("status",healthy.get()?"UP":"DOWN"));
                if (!Boolean.TRUE.equals(a.get("confirmed"))) return ToolResult.success(Map.of("requires_confirmation",true));
                writes.incrementAndGet(); healthy.set(true); return ToolResult.success(Map.of("success",true));
            }
        };
        var health=new Tool() {
            public ToolDefinition definition() { return new ToolDefinition("system.health","test",Map.of()); }
            public boolean requiresExplicitConfirmation(Map<String,Object> a) { return false; }
            public ToolResult execute(ToolCallContext c,Map<String,Object> a) { return ToolResult.success(Map.of("dependencies",Map.of("demo",Map.of("status",healthy.get()?"UP":"DOWN")))); }
        };
        ToolRegistry registry=new DefaultToolRegistry(List.of(guardian,health,new LocalComputerTool(files,Optional.empty())));
        executor=new DefaultToolExecutor(registry);
        runtime=new AgentActionRuntime(store,executions,provider(executor),provider(registry),provider(files),mapper,clock);
    }
    @AfterEach void close() { runtime.close(); }
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p=mock(ObjectProvider.class); when(p.getObject()).thenReturn(value); when(p.getIfAvailable()).thenReturn(value); return p;
    }
    private AgentActionCheckpoint repair() { return runtime.start("owner","chat","test",AgentActionRuntime.repairPlan("demo","restart-demo")); }
    private void advance(AgentActionCheckpoint s) { runtime.advance("owner",s.runId()); }
    private AgentRunStatus status(AgentActionCheckpoint s) { return executions.find("owner",s.runId()).status(); }
    private AgentActionCheckpoint waiting(AgentActionCheckpoint s) { advance(s); advance(s); return runtime.find("owner",s.runId()); }
    @Test void healthyServiceSkipsRemediation() {
        healthy.set(true); var s=repair(); advance(s); advance(s);
        assertEquals(AgentRunStatus.COMPLETED,status(s)); assertEquals(0,writes.get()); assertEquals(2,runtime.find("owner",s.runId()).evidence().size());
    }
    @Test void approvalRunsActionThenReadsHealthBack() {
        var s=waiting(repair()); assertEquals(AgentRunStatus.WAITING_CONFIRMATION,status(s)); assertEquals(0,writes.get());
        runtime.decide("owner",s.runId(),s.digest(),true); advance(s);
        assertEquals(1,writes.get()); assertEquals(AgentRunStatus.COMPLETED,status(s));
        assertTrue(executions.steps("owner",s.runId()).stream().anyMatch(step->step.toolName().equals("system.health")));
    }
    @Test void ownerDigestAndExpiryMustMatch() {
        var s=waiting(repair());
        assertThrows(IllegalArgumentException.class,()->runtime.decide("other",s.runId(),s.digest(),true));
        assertThrows(IllegalArgumentException.class,()->runtime.decide("owner",s.runId(),"wrong",true));
        now.set(now.get().plusSeconds(1801)); assertThrows(IllegalArgumentException.class,()->runtime.decide("owner",s.runId(),s.digest(),true)); assertEquals(0,writes.get());
        runtime.decide("owner",s.runId(),s.digest(),false); assertEquals(AgentRunStatus.CANCELLED,status(s));
    }
    @Test void cancelPreventsAuthorizedWrite() {
        var s=waiting(repair()); runtime.decide("owner",s.runId(),s.digest(),true); runtime.cancel("owner",s.runId()); advance(s);
        assertEquals(AgentRunStatus.CANCELLED,status(s)); assertEquals(0,writes.get());
    }
    @Test void modelCannotApproveItself() {
        var c=new ToolCallContext(new ConversationId("chat"),"model","owner");
        var call=new ToolCall("model","homelab.guardian",Map.of("action","execute","action_id","restart-demo","confirmed",true));
        var result=executor.execute(c,call); assertTrue((Boolean)((Map<?,?>)result.value()).get("requires_confirmation")); assertEquals(0,writes.get());
        executor.executeAuthorized(c,call); assertEquals(1,writes.get());
    }
    @Test void recoveredAppliedWriteIsVerifiedWithoutReplay() {
        var s=waiting(repair()); runtime.decide("owner",s.runId(),s.digest(),true); s=runtime.find("owner",s.runId());
        store.save("owner",s.change("EXECUTING",s.nextStep(),s.prepared(),s.digest(),null,s.evidence())); healthy.set(true); advance(s);
        assertEquals(0,writes.get()); assertEquals(AgentRunStatus.COMPLETED,status(s));
    }
    @Test void uncertainRecoveryStopsAndReviewOnlyReads() {
        var s=waiting(repair()); runtime.decide("owner",s.runId(),s.digest(),true); s=runtime.find("owner",s.runId());
        store.save("owner",s.change("EXECUTING",s.nextStep(),s.prepared(),s.digest(),null,s.evidence())); advance(s);
        assertEquals(0,writes.get()); assertEquals(AgentRunStatus.REVIEW_REQUIRED,status(s));
        healthy.set(true); runtime.review("owner",s.runId()); assertEquals(AgentRunStatus.COMPLETED,status(s)); assertEquals(0,writes.get());
    }
    @Test void exactResourceGrantAuthorizesAction() {
        when(store.consume(eq("owner"),eq("homelab.guardian"),eq("execute"),eq("restart-demo"),any())).thenReturn(true);
        var s=repair(); advance(s); advance(s); assertEquals(1,writes.get()); assertEquals(AgentRunStatus.COMPLETED,status(s));
        verify(store).consume(eq("owner"),eq("homelab.guardian"),eq("execute"),eq("restart-demo"),any());
    }
    @Test void deadlineStopsBeforeAction() {
        var s=repair(); now.set(now.get().plusSeconds(601)); advance(s); assertEquals(AgentRunStatus.LIMIT_REACHED,status(s)); assertEquals(0,writes.get());
    }
    @Test void fileWriteIsVerifiedAndStalePreviewCannotOverwrite() throws Exception {
        var plan=new AgentActionPlan("report",List.of(new AgentActionPlan.Step("write","computer.local",Map.of("action","write","root","work","path","report.txt","content","hello\n"),null,null)),600);
        var s=runtime.start("owner","chat","file",plan); advance(s); s=runtime.find("owner",s.runId()); runtime.decide("owner",s.runId(),s.digest(),true); advance(s);
        assertEquals("hello\n",Files.readString(root.resolve("report.txt"))); assertEquals(AgentRunStatus.COMPLETED,status(s));
        var s2=runtime.start("owner","chat","file2",plan); advance(s2); s2=runtime.find("owner",s2.runId()); Files.writeString(root.resolve("report.txt"),"changed by user");
        runtime.decide("owner",s2.runId(),s2.digest(),true); advance(s2); assertEquals("changed by user",Files.readString(root.resolve("report.txt"))); assertEquals(AgentRunStatus.REVIEW_REQUIRED,status(s2));
    }
    @Test void unverifiedPlansAndMutatingChecksAreRejected() {
        assertThrows(IllegalArgumentException.class,()->new AgentActionPlan.Step("write","computer.local",Map.of("confirmed",true),null,null));
        var plan=new AgentActionPlan("fix",List.of(new AgentActionPlan.Step("fix","homelab.guardian",Map.of("action","execute","action_id","restart-demo"),null,null)),600);
        assertThrows(IllegalArgumentException.class,()->runtime.start("owner","chat","invalid",plan));
        var check=new AgentActionPlan.Check("homelab.guardian",Map.of("action","execute","action_id","x"),"/status","UP");
        var bad=new AgentActionPlan("inspect",List.of(new AgentActionPlan.Step("inspect","system.health",Map.of(),check,null)),600);
        assertThrows(IllegalArgumentException.class,()->runtime.start("owner","chat","bad-check",bad));
    }
    @Test void checkpointReconcilesLostRunStatusUpdateAfterApprovalAndCompletion() {
        var s=waiting(repair());
        store.save("owner",s.change("AUTHORIZED",s.nextStep(),s.prepared(),s.digest(),s.consentExpiresAt(),s.evidence()));
        assertEquals(AgentRunStatus.WAITING_CONFIRMATION,status(s)); advance(s);
        assertEquals(AgentRunStatus.COMPLETED,status(s)); assertEquals(1,writes.get());
        executions.actionStatus("owner",s.runId(),AgentRunStatus.RUNNING,1,"simulate lost status update");
        advance(s); assertEquals(AgentRunStatus.COMPLETED,status(s)); assertEquals(1,writes.get());
    }
    @Test void checkpointReconcilesCancellationBeforeRunStatusWasSaved() {
        var s=waiting(repair()); store.save("owner",s.change("CANCELLED",s.nextStep(),s.prepared(),"",null,s.evidence()));
        advance(s); assertEquals(AgentRunStatus.CANCELLED,status(s)); assertEquals(0,writes.get());
    }
    @Test void checkpointRoundTripRetainsConsent() throws Exception {
        var s=waiting(repair()); var mapper=new ObjectMapper().findAndRegisterModules(); assertEquals(s,mapper.readValue(mapper.writeValueAsString(s),AgentActionCheckpoint.class));
    }
}
