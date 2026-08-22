package com.minikun.agent.minikun_agent.api.openai;

import com.minikun.browser.BrowserContentException;
import com.minikun.browser.BrowserContentService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ChatBrowserFailureFormatter {
    private ChatBrowserFailureFormatter() { }

    static String format(String message, BrowserContentException exception, BrowserContentService browser) {
        String url = "the supplied link";
        if (browser != null) {
            try { url = browser.urlsIn(message).stream().findFirst().orElse(url); }
            catch (BrowserContentException ignored) { }
        }
        log.warn("Browser render failed url={} reason={}", url, exception.getMessage());
        return "ไม่สามารถอ่านลิงก์ได้: " + url + " (" + exception.getMessage() + ")";
    }
}
