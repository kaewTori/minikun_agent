package com.minikun.pcs;

import com.minikun.character.CharacterLoader;
import com.minikun.character.model.CharacterSpecification;
import com.minikun.pcs.model.CapabilityInstruction;
import com.minikun.pcs.model.ConversationContext;
import com.minikun.pcs.model.KnowledgeContext;
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
        String prompt = new PromptComposer().compose(request());

        assertTrue(prompt.indexOf("[Character]") < prompt.indexOf("[Runtime]"));
        assertTrue(prompt.indexOf("[Runtime]") < prompt.indexOf("[Conversation]"));
        assertTrue(prompt.indexOf("[Conversation]") < prompt.indexOf("[Knowledge]"));
        assertTrue(prompt.indexOf("[Knowledge]") < prompt.indexOf("[Capabilities]"));
        assertTrue(prompt.indexOf("[Capabilities]") < prompt.indexOf("[User Message]"));
    }

    @Test
    void omitsEmptyOptionalSections() {
        PromptRequest request = new PromptRequest(
                character(), new RuntimeContext("now"), null, null, List.of(), new UserMessage("hello"));

        String prompt = new PromptComposer().compose(request);

        assertTrue(!prompt.contains("[Conversation]"));
        assertTrue(!prompt.contains("[Knowledge]"));
        assertTrue(!prompt.contains("[Capabilities]"));
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
        String first = new PromptComposer().compose(request);
        String second = new PromptComposer().compose(request);

        assertEquals(first, second);
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