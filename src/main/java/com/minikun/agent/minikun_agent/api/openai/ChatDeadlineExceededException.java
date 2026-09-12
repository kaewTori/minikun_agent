package com.minikun.agent.minikun_agent.api.openai;

/** Signals that a child chat stage has outlived the request's shared deadline. */
final class ChatDeadlineExceededException extends RuntimeException {
    ChatDeadlineExceededException(String stage) {
        super("chat deadline exceeded at " + (stage == null || stage.isBlank() ? "unknown" : stage));
    }
}
