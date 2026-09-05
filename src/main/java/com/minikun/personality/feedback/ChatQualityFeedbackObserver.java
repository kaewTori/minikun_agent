package com.minikun.personality.feedback;

/** Optional, non-blocking observer for evaluating routing quality from real user feedback. */
public interface ChatQualityFeedbackObserver {
    void observe(ChatFeedback feedback);
}
