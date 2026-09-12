package com.minikun.agent.minikun_agent.api.openai;

import java.util.Locale;
import java.util.Objects;

/** Prevents model-specific tool protocols from leaking into user-facing text. */
final class ModelOutputSanitizer {
    private static final String[] PROTOCOL_MARKERS = {
            "<|tool", "tool*call", "googlesearch", "imagesretrieved", "imagegeneration"
    };

    private ModelOutputSanitizer() { }

    static String clean(String value) {
        return new Stream().accept(value);
    }

    static final class Stream {
        private boolean protocolStarted;

        String accept(String value) {
            if (protocolStarted) return "";
            String text = Objects.requireNonNullElse(value, "");
            int marker = firstMarker(text);
            if (marker < 0) return text;
            protocolStarted = true;
            return text.substring(0, marker).stripTrailing();
        }

        private int firstMarker(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            int first = -1;
            for (String marker : PROTOCOL_MARKERS) {
                int index = lower.indexOf(marker);
                if (index >= 0 && (first < 0 || index < first)) first = index;
            }
            return first;
        }
    }
}
