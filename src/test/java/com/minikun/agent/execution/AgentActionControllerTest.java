package com.minikun.agent.execution;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import com.minikun.sync.DevicePairingService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

class AgentActionControllerTest {
    private ObjectProvider<DevicePairingService> provider(DevicePairingService pairing) {
        ObjectProvider<DevicePairingService> p=mock(ObjectProvider.class); when(p.getIfAvailable()).thenReturn(pairing); return p;
    }
    @Test void newOperationalEndpointsFailClosedAndRejectCrossOrigin() {
        var runtime=mock(AgentActionRuntime.class); var executions=mock(AgentExecutionService.class);
        var controller=new AgentActionController(runtime,executions,provider(null),"");
        assertThrows(ResponseStatusException.class,()->controller.list(new MockHttpServletRequest(),"owner"));
        verifyNoInteractions(runtime,executions);
        var trusted=new AgentActionController(runtime,executions,provider(null),"test-only-token");
        var request=new MockHttpServletRequest(); request.addHeader("X-Minikun-Agent-Token","test-only-token"); request.addHeader("Origin","https://another.example");
        assertThrows(ResponseStatusException.class,()->trusted.list(request,"owner")); verifyNoInteractions(runtime,executions);
    }
    @Test void pairedDevicesCannotActForOtherOwners() {
        var pairing=mock(DevicePairingService.class); var request=new MockHttpServletRequest();
        when(pairing.require(request)).thenReturn(new DevicePairingService.Session(UUID.randomUUID(),"owner-a","test"));
        var runtime=mock(AgentActionRuntime.class); var executions=mock(AgentExecutionService.class);
        var controller=new AgentActionController(runtime,executions,provider(pairing),"");
        assertThrows(ResponseStatusException.class,()->controller.list(request,"owner-b")); verifyNoInteractions(runtime,executions);
        controller.grants(request,"owner-a"); verify(runtime).grants("owner-a");
    }
    @Test void decisionsUsePersistedDigestRatherThanToolArguments() {
        var runtime=mock(AgentActionRuntime.class); var controller=new AgentActionController(runtime,mock(AgentExecutionService.class),provider(null),"test-only-token");
        var request=new MockHttpServletRequest(); request.addHeader("X-Minikun-Agent-Token","test-only-token"); var id=UUID.randomUUID();
        controller.decide(request,"owner",id,new AgentActionController.Decision("preview-digest",true));
        verify(runtime).decide("owner",id,"preview-digest",true);
    }
}
