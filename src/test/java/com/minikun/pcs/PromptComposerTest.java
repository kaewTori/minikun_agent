package com.minikun.pcs;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
import com.minikun.pcs.model.Prompt;
import com.minikun.pcs.model.PromptRole;
import com.minikun.pcs.model.RuntimeContext;
import com.minikun.pcs.model.UserMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptComposerTest {
    private static final Path MCS_ROOT = Path.of("../../config/minikun-agent/mcs");

    @Test
    void composesSectionsInFixedOrder() {
        Prompt prompt = new PromptComposer().compose(request());
        assertEquals(2, prompt.messages().size());
        assertEquals(PromptRole.SYSTEM, prompt.messages().get(0).role());
        assertEquals(PromptRole.USER, prompt.messages().get(1).role());

        String system = prompt.messages().get(0).content();
        assertTrue(system.indexOf("[Character]") < system.indexOf("[Runtime]"));
        assertTrue(system.indexOf("[Runtime]") < system.indexOf("[Conversation]"));
        assertTrue(system.indexOf("[Conversation]") < system.indexOf("[Knowledge]"));
        assertTrue(system.indexOf("[Knowledge]") < system.indexOf("[Capabilities]"));
        assertEquals("Answer this", prompt.messages().get(1).content());
    }

    @Test
    void omitsEmptyOptionalSections() {
        PromptRequest request = new PromptRequest(
                character(), new RuntimeContext("now"), null, null, List.of(), new UserMessage("hello"));

        Prompt prompt = new PromptComposer().compose(request);
        String system = prompt.messages().get(0).content();

        assertTrue(!system.contains("[Conversation]"));
        assertTrue(!system.contains("[Knowledge]"));
        assertTrue(!system.contains("[Capabilities]"));
    }

    @Test
    void rejectsInvalidRequiredInputs() {
        assertThrows(PromptException.class, () -> new PromptComposer().compose(null));
        assertThrows(PromptException.class, () -> new PromptComposer().compose(
                new PromptRequest(null, new RuntimeContext("now"), null, null, List.of(), new UserMessage("hello"))));
        assertThrows(PromptException.class, () -> new PromptComposer().compose(
                new PromptRequest(character(), new RuntimeContext(" "), null, null, List.of(), new UserMessage("hello"))));
        assertThrows(PromptException.class, () -> new PromptComposer().compose(
                new PromptRequest(character(), new RuntimeContext("now"), null, null, List.of(), new UserMessage(" "))));
    }

    @Test
    void requestCopiesCapabilityListAndOutputIsDeterministic() {
        PromptRequest request = request();
        Prompt first = new PromptComposer().compose(request);
        Prompt second = new PromptComposer().compose(request);

        assertEquals(first, second);
        assertThrows(UnsupportedOperationException.class,
            () -> first.messages().add(first.messages().get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> request.capabilities().add(new CapabilityInstruction("later", "instruction")));
    }

    private PromptRequest request() {
        return new PromptRequest(character(), new RuntimeContext("2026-08-01"),
            new ConversationContext("Previous turn"), new KnowledgeContext("Retrieved fact"),
                List.of(new CapabilityInstruction("search", "Use retrieved sources")),
                new UserMessage("Answer this"));
    }

    private CharacterSpecification character() {
        return new CharacterLoader(MCS_ROOT).load();
    }
}