package com.minikun.agent.minikun_agent.api.openai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.conversation.ChatMessage;

/** Selects a recent, turn-aware conversation window without dropping all history at once. */
final class ConversationHistoryWindow {
    private static final String SEPARATOR = "\n\n";
    private static final String EARLIER_MESSAGES_OMITTED = "[Earlier conversation omitted]";
    private static final String MESSAGE_SHORTENED = "\n…[message shortened]…\n";

    Result build(
            ChatCompletionRequest request,
            List<ChatMessage> serverHistory,
            Predicate<String> excludedMessage,
            long maximumCharacters) {
        return build(request, serverHistory, excludedMessage, maximumCharacters, Integer.MAX_VALUE);
    }

    Result build(
            ChatCompletionRequest request,
            List<ChatMessage> serverHistory,
            Predicate<String> excludedMessage,
            long maximumCharacters,
            int maximumMessages) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(excludedMessage, "excluded message predicate must not be null");
        if (maximumMessages <= 0) {
            throw new IllegalArgumentException("maximum messages must be positive");
        }

        List<ChatMessage> clientHistory = usable(requestHistory(request), excludedMessage);
        List<ChatMessage> storedHistory = completedStoredHistory(usable(serverHistory, excludedMessage));
        HistorySelection selection = reconcile(storedHistory, clientHistory);
        Source source = selection.source();
        List<ChatMessage> selectedSource = selection.messages();
        List<ChatMessage> recentSource = recent(selectedSource, maximumMessages);
        Result result = window(recentSource, source, maximumCharacters);
        return new Result(result.content(), source, selectedSource.size(), result.selectedMessages(),
                Math.max(0, selectedSource.size() - result.selectedMessages()), result.messages());
    }

    /**
     * Keeps the visible client transcript authoritative when histories diverge, while
     * recovering older persisted turns when the client only sends a recent suffix.
     */
    private HistorySelection reconcile(
            List<ChatMessage> storedHistory,
            List<ChatMessage> clientHistory) {
        if (clientHistory.isEmpty()) {
            return storedHistory.isEmpty()
                    ? new HistorySelection(List.of(), Source.NONE)
                    : new HistorySelection(storedHistory, Source.SERVER);
        }
        if (storedHistory.isEmpty()) {
            return new HistorySelection(clientHistory, Source.CLIENT);
        }

        int overlap = suffixPrefixOverlap(storedHistory, clientHistory);
        if (overlap == clientHistory.size()) {
            return new HistorySelection(storedHistory, Source.SERVER);
        }
        if (overlap > 0) {
            List<ChatMessage> merged = new ArrayList<>(storedHistory.size()
                    + clientHistory.size() - overlap);
            merged.addAll(storedHistory);
            merged.addAll(clientHistory.subList(overlap, clientHistory.size()));
            return new HistorySelection(List.copyOf(merged), Source.MERGED);
        }

        // An edited branch or unrelated persisted transcript must never leak into the
        // conversation currently visible to the user.
        return new HistorySelection(clientHistory, Source.CLIENT);
    }

    private int suffixPrefixOverlap(
            List<ChatMessage> storedHistory,
            List<ChatMessage> clientHistory) {
        int maximum = Math.min(storedHistory.size(), clientHistory.size());
        for (int length = maximum; length > 0; length--) {
            int storedStart = storedHistory.size() - length;
            boolean matches = true;
            for (int offset = 0; offset < length; offset++) {
                if (!sameMessage(storedHistory.get(storedStart + offset), clientHistory.get(offset))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return length;
            }
        }
        return 0;
    }

    private boolean sameMessage(ChatMessage left, ChatMessage right) {
        return left.role().equalsIgnoreCase(right.role())
                && left.content().strip().equals(right.content().strip());
    }

    private List<ChatMessage> recent(List<ChatMessage> messages, int maximumMessages) {
        int from = Math.max(0, messages.size() - maximumMessages);
        return messages.subList(from, messages.size());
    }

    /** Repairs legacy failed requests that persisted a trailing user message without an answer. */
    private List<ChatMessage> completedStoredHistory(List<ChatMessage> messages) {
        int end = messages.size();
        while (end > 0 && "user".equalsIgnoreCase(messages.get(end - 1).role())) {
            end--;
        }
        return end == messages.size() ? messages : messages.subList(0, end);
    }

    private Result window(List<ChatMessage> history, Source source, long maximumCharacters) {
        if (history.isEmpty() || maximumCharacters <= 0) {
            return new Result("", source, history.size(), 0, history.size(), List.of());
        }
        int limit = (int) Math.min(maximumCharacters, Integer.MAX_VALUE);
        List<String> rendered = history.stream().map(this::render).toList();
        String complete = String.join(SEPARATOR, rendered);
        if (complete.length() <= limit) {
            return new Result(complete, source, history.size(), history.size(), 0, List.copyOf(history));
        }

        String prefix = EARLIER_MESSAGES_OMITTED + SEPARATOR;
        int contentLimit = Math.max(0, limit - prefix.length());
        if (contentLimit == 0) {
            return new Result(EARLIER_MESSAGES_OMITTED.substring(
                    0, Math.min(limit, EARLIER_MESSAGES_OMITTED.length())),
                    source, history.size(), 0, history.size(), List.of());
        }

        Deque<String> selected = new ArrayDeque<>();
        Deque<ChatMessage> selectedHistory = new ArrayDeque<>();
        int used = 0;
        for (int index = rendered.size() - 1; index >= 0; index--) {
            String message = rendered.get(index);
            int separatorCharacters = selected.isEmpty() ? 0 : SEPARATOR.length();
            int available = contentLimit - used - separatorCharacters;
            if (available <= 0) {
                break;
            }
            if (message.length() > available) {
                if (selected.isEmpty()) {
                    String shortened = shorten(message, available);
                    selected.addFirst(shortened);
                    selectedHistory.addFirst(shortenedMessage(history.get(index), shortened));
                    used = contentLimit;
                }
                break;
            }
            selected.addFirst(message);
            selectedHistory.addFirst(history.get(index));
            used += separatorCharacters + message.length();
        }

        String content = prefix + String.join(SEPARATOR, selected);
        int selectedMessages = selected.size();
        return new Result(content, source, history.size(), selectedMessages,
                Math.max(0, history.size() - selectedMessages), List.copyOf(selectedHistory));
    }

    private ChatMessage shortenedMessage(ChatMessage original, String rendered) {
        String prefix = original.role().toLowerCase(Locale.ROOT) + ": ";
        String content = rendered.startsWith(prefix) ? rendered.substring(prefix.length()) : rendered;
        if (content.isBlank()) {
            content = "…";
        }
        return new ChatMessage(original.role(), content);
    }

    private List<ChatMessage> requestHistory(ChatCompletionRequest request) {
        int currentUserIndex = lastUserMessageIndex(request);
        List<ChatMessage> messages = new ArrayList<>();
        for (int index = 0; index < currentUserIndex; index++) {
            var message = request.messages().get(index);
            if (hasText(message.role()) && hasText(message.content())) {
                messages.add(new ChatMessage(message.role(), message.content()));
            }
        }
        return List.copyOf(messages);
    }

    private int lastUserMessageIndex(ChatCompletionRequest request) {
        for (int index = request.messages().size() - 1; index >= 0; index--) {
            var message = request.messages().get(index);
            if ("user".equalsIgnoreCase(message.role())
                    && (hasText(message.content()) || message.hasImageContent())) {
                return index;
            }
        }
        return request.messages().size();
    }

    private List<ChatMessage> usable(
            List<ChatMessage> messages,
            Predicate<String> excludedMessage) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .filter(message -> isConversationRole(message.role()))
                .filter(message -> hasText(message.content()))
                .filter(message -> !excludedMessage.test(message.content()))
                .toList();
    }

    private boolean isConversationRole(String role) {
        return "user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role);
    }

    private String render(ChatMessage message) {
        return message.role().toLowerCase(Locale.ROOT) + ": " + message.content().strip();
    }

    private String shorten(String content, int maximumCharacters) {
        if (content.length() <= maximumCharacters) {
            return content;
        }
        if (maximumCharacters <= MESSAGE_SHORTENED.length() + 2) {
            return content.substring(Math.max(0, content.length() - maximumCharacters));
        }
        int remaining = maximumCharacters - MESSAGE_SHORTENED.length();
        int prefixCharacters = Math.max(1, remaining * 2 / 3);
        int suffixCharacters = remaining - prefixCharacters;
        return content.substring(0, prefixCharacters)
                + MESSAGE_SHORTENED
                + content.substring(content.length() - suffixCharacters);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    enum Source {
        CLIENT,
        SERVER,
        MERGED,
        NONE
    }

    private record HistorySelection(List<ChatMessage> messages, Source source) {
    }

    record Result(
            String content,
            Source source,
            int inputMessages,
            int selectedMessages,
            int omittedMessages,
            List<ChatMessage> messages) {
    }
}
