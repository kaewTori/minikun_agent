package com.minikun.agent.minikun_agent.api.openai;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;

/** Runs chat turns independently from the browser connection. */
@Service
public final class BackgroundChatService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundChatService.class);
    private static final long RESULT_TTL_HOURS = 24;
    private static final Duration DEFAULT_JOB_TIMEOUT = Duration.ofSeconds(180);

    private final ChatService chatService;
    private final NotificationDispatcher notifications;
    private final BackgroundChatStore store;
    private final Duration jobTimeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "minikun-background-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public BackgroundChatService(ChatService chatService, NotificationDispatcher notifications) {
        this(chatService, notifications, (BackgroundChatStore) null, DEFAULT_JOB_TIMEOUT);
    }

    @Autowired
    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            ObjectProvider<BackgroundChatStore> store,
            @Value("${minikun.chat.background.timeout:180s}") Duration jobTimeout) {
        this(chatService, notifications, store == null ? null : store.getIfAvailable(), jobTimeout);
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications, BackgroundChatStore store) {
        this(chatService, notifications, store, DEFAULT_JOB_TIMEOUT);
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            BackgroundChatStore store, Duration jobTimeout) {
        this.chatService = Objects.requireNonNull(chatService, "chat service must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification dispatcher must not be null");
        this.store = store;
        if (jobTimeout == null || jobTimeout.isZero() || jobTimeout.isNegative()) {
            throw new IllegalArgumentException("background chat timeout must be positive");
        }
        this.jobTimeout = jobTimeout;
    }

    public UUID submit(ChatCompletionRequest request, ConversationId conversationId) {
        UUID id = UUID.randomUUID();
        Job job = new Job();
        jobs.put(id, job);
        if (store != null) store.create(id, request, conversationId.value());
        job.runner = executor.submit(() -> run(id, job, request, conversationId));
        timeoutAfter(id, job);
        return id;
    }

    public Optional<View> find(UUID id) {
        Job job = jobs.get(id);
        if (job != null) return Optional.of(job.view(id));
        return store == null ? Optional.empty() : store.find(id).map(this::view);
    }

    public boolean cancel(UUID id) {
        Job job = jobs.get(id);
        if (job == null) return false;
        if (job.state.compareAndSet(State.RUNNING, State.CANCELLED)) {
            if (job.runner != null) job.runner.cancel(true);
            if (store != null) store.update(id, "CANCELLED", null, "cancelled by user");
            expire(id, job);
            return true;
        }
        return jobs.remove(id, job);
    }

    private void run(UUID id, Job job, ChatCompletionRequest request, ConversationId conversationId) {
        try {
            job.response = chatService.chatCompletion(request, conversationId);
            if (job.state.compareAndSet(State.RUNNING, State.COMPLETED)) {
                if (store != null) store.update(id, "COMPLETED", job.response, "");
                notify(id, "ตอบคำถามเสร็จแล้วครับ เปิดมินิคุงเพื่ออ่านคำตอบ");
            }
        } catch (RuntimeException exception) {
            if (shuttingDown) {
                LOGGER.info("process=background_chat event=paused_for_restart job_id={}", id);
                return;
            }
            String error = Objects.requireNonNullElse(exception.getMessage(), "ตอบคำถามไม่สำเร็จ");
            if (job.state.compareAndSet(State.RUNNING, State.FAILED)) {
                job.error = error;
                if (store != null) store.update(id, "FAILED", null, job.error);
                notify(id, "ตอบคำถามไม่สำเร็จครับ กลับมาที่มินิคุงเพื่อลองอีกครั้ง");
            }
        } finally {
            expire(id, job);
        }
    }

    public boolean resume(UUID id) {
        if (store == null || jobs.containsKey(id)) return false;
        return store.find(id).filter(job -> "FAILED".equals(job.status()) || "CANCELLED".equals(job.status()))
                .map(job -> { start(job); return true; }).orElse(false);
    }

    @EventListener(ApplicationReadyEvent.class)
    void resumePending() {
        if (store == null) return;
        store.deleteExpired();
        store.pending().stream().filter(job -> !jobs.containsKey(job.id())).forEach(this::start);
    }

    private void start(BackgroundChatStore.SavedJob saved) {
        Job job = new Job();
        jobs.put(saved.id(), job);
        store.create(saved.id(), saved.request(), saved.conversationId());
        job.runner = executor.submit(() -> run(saved.id(), job, saved.request(), new ConversationId(saved.conversationId())));
        timeoutAfter(saved.id(), job);
    }

    private View view(BackgroundChatStore.SavedJob saved) {
        return new View(saved.id(), saved.status().toLowerCase(java.util.Locale.ROOT), saved.response(), saved.error());
    }

    private void notify(UUID id, String message) {
        try {
            notifications.publish(new NotificationRequest(
                    "CHAT", id.toString(), NotificationChannel.REMINDER,
                    "Mini-kun", message, 3, "speech_balloon"));
        } catch (RuntimeException exception) {
            LOGGER.warn("process=background_chat event=notification_failed job_id={} reason={}",
                    id, exception.getMessage());
        }
    }

    private void expire(UUID id, Job job) {
        java.util.concurrent.CompletableFuture.delayedExecutor(RESULT_TTL_HOURS, TimeUnit.HOURS)
                .execute(() -> jobs.remove(id, job));
    }

    private void timeoutAfter(UUID id, Job job) {
        timeoutExecutor.schedule(() -> {
            if (!job.state.compareAndSet(State.RUNNING, State.FAILED)) return;
            job.error = "ตอบคำถามใช้เวลานานเกินไป กรุณาลองใหม่อีกครั้ง";
            if (job.runner != null) job.runner.cancel(true);
            if (store != null) store.update(id, "FAILED", null, job.error);
            LOGGER.warn("process=background_chat event=timeout job_id={} timeout_ms={}",
                    id, jobTimeout.toMillis());
            notify(id, "ตอบคำถามใช้เวลานานเกินไปครับ กลับมาที่มินิคุงเพื่อลองใหม่อีกครั้ง");
        }, Math.max(1L, jobTimeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        shuttingDown = true;
        timeoutExecutor.shutdownNow();
        executor.shutdownNow();
    }

    public record View(UUID id, String status, ChatCompletionResponse response, String error) { }

    private enum State { RUNNING, COMPLETED, FAILED, CANCELLED }

    private static final class Job {
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private volatile ChatCompletionResponse response;
        private volatile String error = "";
        private volatile Future<?> runner;

        private View view(UUID id) {
            return new View(id, state.get().name().toLowerCase(java.util.Locale.ROOT), response, error);
        }
    }
}
