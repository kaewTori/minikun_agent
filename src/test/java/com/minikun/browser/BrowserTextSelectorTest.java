package com.minikun.browser;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BrowserTextSelectorTest {
    @Test
    void selectsMatchingMiddleAndTailPassagesRatherThanOnlyPagePrefix() {
        String text = "Introduction. ".repeat(400) + "Battery lifespan is 15 hours. " + "Noise. ".repeat(600)
                + "ราคาสินค้า 1200 บาท พร้อมรับประกันสองปี";
        String selected = BrowserTextSelector.select(text, "battery lifespan ราคาสินค้า https://example.com", 2000);
        assertTrue(selected.contains("Battery lifespan is 15 hours"));
        assertTrue(selected.contains("1200 บาท"));
        assertTrue(selected.length() <= 2000);
        assertTrue(selected.startsWith("Introduction"));
    }

    @Test
    void samplesEndOfPageForGenericSummaryAndPreservesSmallPages() {
        String text = "Introduction. ".repeat(400) + "Final conclusions";
        assertTrue(BrowserTextSelector.select(text, "สรุป", 1200).contains("Final conclusions"));
        assertEquals("Tiny page", BrowserTextSelector.select("Tiny page", "", 100));
    }

    @Test
    void handlesTinyBudgetsWithoutSplittingSurrogatePairs() {
        assertEquals("", BrowserTextSelector.select("😀hello", "", 1));
        assertEquals("😀", BrowserTextSelector.select("😀hello", "", 2));
    }
}
