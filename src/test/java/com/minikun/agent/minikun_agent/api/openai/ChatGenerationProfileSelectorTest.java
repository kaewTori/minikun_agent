package com.minikun.agent.minikun_agent.api.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minikun.model.CooperationRouter;
import com.minikun.personality.companion.CompanionMode;
import org.junit.jupiter.api.Test;

class ChatGenerationProfileSelectorTest {
    private final ChatGenerationProfileSelector selector = new ChatGenerationProfileSelector(
            new CooperationRouter(), true, 384, 512, 1_536, 1_536, 2_048, 4_096);

    @Test
    void usesSmallCeilingsForCompanionAndGeneralConversation() {
        assertEquals(new ChatGenerationProfileSelector.Selection("companion", 384),
                selector.select("วันนี้เหนื่อยจัง", CompanionMode.COMPANION, false, 2_048));
        assertEquals(new ChatGenerationProfileSelector.Selection("general", 1_536),
                selector.select("วันนี้กินอะไรดี", null, false, 4_096));
    }

    @Test
    void preservesTechnicalAndCreativeCapacity() {
        assertEquals(new ChatGenerationProfileSelector.Selection("technical", 2_048),
                selector.select("ช่วย debug Spring Boot API นี้", CompanionMode.COMPANION, false, 4_096));
        assertEquals(new ChatGenerationProfileSelector.Selection("creative", 4_096),
                selector.select("ช่วยแต่งเรื่องสั้นแฟนตาซี", null, false, 4_096));
        assertEquals(new ChatGenerationProfileSelector.Selection("creative", 4_096),
                selector.select("ช่วยเล่าเรื่องแมวให้ฟังหน่อย", null, false, 4_096));
    }

    @Test
    void givesToolsAndWorkModeAWorkSizedBudget() {
        assertEquals(new ChatGenerationProfileSelector.Selection("work", 1_536),
                selector.select("สรุปผลให้หน่อย", null, true, 2_048));
        assertEquals(new ChatGenerationProfileSelector.Selection("work", 1_536),
                selector.select("ช่วยจัดงานนี้", CompanionMode.WORK, false, 2_048));
    }

    @Test
    void neverExceedsTheApplicationMaximum() {
        assertEquals(new ChatGenerationProfileSelector.Selection("technical", 600),
                selector.select("ช่วย debug Java code", null, false, 600));
    }
}
