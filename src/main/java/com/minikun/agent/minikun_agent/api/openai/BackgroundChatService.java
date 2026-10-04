package com.minikun.agent.minikun_agent.api.openai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.minikun.agent.minikun_agent.api.openai.dto.ChatAttachment;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionRequest;
import com.minikun.agent.minikun_agent.api.openai.dto.ChatCompletionResponse;
import com.minikun.agent.minikun_agent.api.openai.dto.Message;
import com.minikun.agent.minikun_agent.conversation.ConversationId;
import com.minikun.notification.NotificationChannel;
import com.minikun.notification.NotificationDispatcher;
import com.minikun.notification.NotificationRequest;
import com.minikun.visual.StoryIllustrationService;

/** Runs chat turns independently from the browser connection. */
@Service
public final class BackgroundChatService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundChatService.class);
    private static final long RESULT_TTL_HOURS = 24;
    private static final Duration DEFAULT_JOB_TIMEOUT = Duration.ofMinutes(15);
    private static final int DEFAULT_MAX_BACKGROUND_QUEUE = 32;
    private static final int DEFAULT_MAX_ACTIVE_BACKGROUND = 8;
    private static final int DEFAULT_MAX_IMAGE_QUEUE = 8;
    private static final int DEFAULT_MAX_ACTIVE_IMAGES = 1;
    private static final String BACKGROUND_QUEUE_FULL = "คิวงานเบื้องหลังเต็ม กรุณาลองใหม่ภายหลัง";

    private final ChatService chatService;
    private final NotificationDispatcher notifications;
    private final BackgroundChatStore store;
    private final Duration jobTimeout;
    private final int maxBackgroundQueue;
    private final Semaphore backgroundSlots;
    private final int maxImageQueue;
    private final Semaphore imageSlots;
    private final ChatPerformanceMetrics metrics;
    private final AtomicInteger queuedChats = new AtomicInteger();
    private final AtomicInteger activeChats = new AtomicInteger();
    private final AtomicInteger queuedImages = new AtomicInteger();
    private final AtomicInteger activeImages = new AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "minikun-background-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown;

    public BackgroundChatService(ChatService chatService, NotificationDispatcher notifications) {
        this(chatService, notifications, (BackgroundChatStore) null, DEFAULT_JOB_TIMEOUT,
                DEFAULT_MAX_BACKGROUND_QUEUE, DEFAULT_MAX_ACTIVE_BACKGROUND,
                DEFAULT_MAX_IMAGE_QUEUE, DEFAULT_MAX_ACTIVE_IMAGES, null);
    }

    @Autowired
    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            ObjectProvider<BackgroundChatStore> store,
            @Value("${minikun.chat.background.timeout:15m}") Duration jobTimeout,
            @Value("${minikun.chat.background.max-queued:32}") int maxBackgroundQueue,
            @Value("${minikun.chat.background.max-active:8}") int maxActiveBackground,
            @Value("${minikun.visual.generation.async.max-queued:8}") int maxImageQueue,
            @Value("${minikun.visual.generation.async.max-active:1}") int maxActiveImages,
            ObjectProvider<ChatPerformanceMetrics> metrics) {
        this(chatService, notifications, store == null ? null : store.getIfAvailable(), jobTimeout,
                maxBackgroundQueue, maxActiveBackground, maxImageQueue, maxActiveImages,
                metrics == null ? null : metrics.getIfAvailable());
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications, BackgroundChatStore store) {
        this(chatService, notifications, store, DEFAULT_JOB_TIMEOUT,
                DEFAULT_MAX_BACKGROUND_QUEUE, DEFAULT_MAX_ACTIVE_BACKGROUND,
                DEFAULT_MAX_IMAGE_QUEUE, DEFAULT_MAX_ACTIVE_IMAGES, null);
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            ObjectProvider<BackgroundChatStore> store, Duration jobTimeout) {
        this(chatService, notifications, store == null ? null : store.getIfAvailable(), jobTimeout,
                DEFAULT_MAX_BACKGROUND_QUEUE, DEFAULT_MAX_ACTIVE_BACKGROUND,
                DEFAULT_MAX_IMAGE_QUEUE, DEFAULT_MAX_ACTIVE_IMAGES, null);
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            BackgroundChatStore store, Duration jobTimeout) {
        this(chatService, notifications, store, jobTimeout,
                DEFAULT_MAX_BACKGROUND_QUEUE, DEFAULT_MAX_ACTIVE_BACKGROUND,
                DEFAULT_MAX_IMAGE_QUEUE, DEFAULT_MAX_ACTIVE_IMAGES, null);
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            BackgroundChatStore store, Duration jobTimeout, int maxImageQueue, int maxActiveImages) {
        this(chatService, notifications, store, jobTimeout,
                DEFAULT_MAX_BACKGROUND_QUEUE, DEFAULT_MAX_ACTIVE_BACKGROUND,
                maxImageQueue, maxActiveImages, null);
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            BackgroundChatStore store, Duration jobTimeout, int maxImageQueue, int maxActiveImages,
            ChatPerformanceMetrics metrics) {
        this(chatService, notifications, store, jobTimeout,
                DEFAULT_MAX_BACKGROUND_QUEUE, DEFAULT_MAX_ACTIVE_BACKGROUND,
                maxImageQueue, maxActiveImages, metrics);
    }

    BackgroundChatService(ChatService chatService, NotificationDispatcher notifications,
            BackgroundChatStore store, Duration jobTimeout, int maxBackgroundQueue,
            int maxActiveBackground, int maxImageQueue, int maxActiveImages,
            ChatPerformanceMetrics metrics) {
        this.chatService = Objects.requireNonNull(chatService, "chat service must not be null");
        this.notifications = Objects.requireNonNull(notifications, "notification dispatcher must not be null");
        this.store = store;
        if (jobTimeout == null || jobTimeout.isZero() || jobTimeout.isNegative()) {
            throw new IllegalArgumentException("background chat timeout must be positive");
        }
        if (maxBackgroundQueue < 1 || maxActiveBackground < 1
                || maxImageQueue < 1 || maxActiveImages < 1) {
            throw new IllegalArgumentException("background and image queue limits must be positive");
        }
        this.jobTimeout = jobTimeout;
        this.maxBackgroundQueue = maxBackgroundQueue;
        this.backgroundSlots = new Semaphore(maxActiveBackground, true);
        this.maxImageQueue = maxImageQueue;
        this.imageSlots = new Semaphore(maxActiveImages, true);
        this.metrics = metrics;
        publishBackgroundMetrics();
        publishImageMetrics();
    }

    public UUID submit(ChatCompletionRequest request, ConversationId conversationId) {
        return submit(request, conversationId, "");
    }

    /** Returns an existing job for a repeated idempotency key within this process. */
    public synchronized UUID submit(ChatCompletionRequest request, ConversationId conversationId,
            String idempotencyKey) {
        Objects.requireNonNull(request, "chat request must not be null");
        Objects.requireNonNull(conversationId, "conversation id must not be null");
        String ownerId = request.owner_id() == null ? "" : request.owner_id().strip();
        if (store != null) {
            Optional<BackgroundChatStore.SavedJob> existing = store.findByIdempotency(
                    ownerId, conversationId.value(), idempotencyKey);
            if (existing.isPresent()) return existing.get().id();
        }
        UUID id = UUID.randomUUID();
        Job job = new Job();
        job.deadline = java.time.Instant.now().plus(jobTimeout);
        jobs.put(id, job);
        if (store != null) {
            store.create(id, request, conversationId.value(), idempotencyKey, ownerId, job.deadline, "QUEUED");
        }
        if (!reserveBackgroundQueue(job)) {
            rejectChat(id, job);
            return id;
        }
        try {
            job.runner = executor.submit(() -> run(id, job, request, conversationId, false));
        } catch (RejectedExecutionException exception) {
            releaseWaiting(job);
            failChat(id, job, "ระบบกำลังปิดเพื่อรีสตาร์ต กรุณาลองใหม่อีกครั้ง");
            return id;
        }
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
        State cancelledFrom = cancelChat(job);
        if (cancelledFrom != null) {
            releaseWaiting(job);
            if (job.runner != null) job.runner.cancel(true);
            if (store != null) store.update(id, "CANCELLED", null, "cancelled by user");
            if (cancelledFrom == State.QUEUED) job.finished = true;
            expire(id, job);
            return true;
        }
        if (job.state.get() == State.COMPLETED && imagePending(job)) {
            ImageState imageCancelledFrom = cancelImage(job);
            if (imageCancelledFrom == null) return false;
            releaseImageWaiting(job);
            if (job.imageRunner != null) job.imageRunner.cancel(true);
            if (store != null) store.updateImage(id, "CANCELLED", job.response, "cancelled by user");
            if (!job.imageActiveCounted.get()) job.imageFinished = true;
            if (metrics != null) metrics.imageOutcome("cancelled");
            notify(id, "ยกเลิกการสร้างภาพแล้วครับ");
            return true;
        }
        return job.finished && !imagePending(job) && jobs.remove(id, job);
    }

    private void run(UUID id, Job job, ChatCompletionRequest request, ConversationId conversationId, boolean replay) {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.put("job_id", id.toString());
        MDC.put("trace_id", id.toString());
        boolean acquired = false;
        try (var scope = new com.minikun.tools.BackgroundToolScope(id, replay,
                () -> job.state.get() == State.RUNNING && !shuttingDown)) {
            if (shuttingDown || job.state.get() != State.QUEUED) return;
            long remainingMillis = remainingMillis(job.deadline);
            if (remainingMillis < 1L) {
                failChat(id, job, "ตอบคำถามใช้เวลานานเกินไป กรุณาลองใหม่อีกครั้ง");
                return;
            }
            if (!backgroundSlots.tryAcquire(remainingMillis, TimeUnit.MILLISECONDS)) {
                failChat(id, job, "คิวงานเบื้องหลังหมดเวลา กรุณาลองใหม่อีกครั้ง");
                return;
            }
            acquired = true;
            releaseWaiting(job);
            job.activeCounted.set(true);
            activeChats.incrementAndGet();
            publishBackgroundMetrics();
            if (!job.state.compareAndSet(State.QUEUED, State.RUNNING)) return;
            if (shuttingDown) {
                job.state.compareAndSet(State.RUNNING, State.QUEUED);
                return;
            }
            if (store != null) store.update(id, "RUNNING", job.response, "");
            ChatService.ChatCompletionOutcome outcome = chatService.chatCompletionInBackground(
                    request, conversationId, new ChatRequestContext(
                            id.toString(), id.toString(), job.deadline == null
                                    ? java.time.Instant.now().plus(jobTimeout) : job.deadline));
            // Keep older integrations and test doubles compatible with the original method.
            if (outcome == null) {
                outcome = new ChatService.ChatCompletionOutcome(
                        chatService.chatCompletion(request, conversationId), null);
            }
            job.response = outcome.response();
            if (scope.blocked()) {
                if (job.state.compareAndSet(State.RUNNING, State.REVIEW_REQUIRED)) {
                    job.error = "งานนี้อาจทำสำเร็จไปบางส่วนแล้ว กรุณาตรวจผลเดิมก่อนสั่งเปลี่ยนข้อมูลอีกครั้ง";
                    if (store != null) store.update(id, "REVIEW_REQUIRED", job.response, job.error);
                    if (metrics != null) metrics.backgroundOutcome("review_required");
                    notify(id, job.error);
                }
            } else if (job.response == null) {
                failChat(id, job, "ไม่พบคำตอบจาก chat service");
            } else if (job.state.compareAndSet(State.RUNNING, State.COMPLETED)) {
                if (store != null) store.complete(id, job.response, outcome.illustrationTask() != null);
                if (metrics != null) metrics.backgroundOutcome("completed");
                if (outcome.illustrationTask() != null) {
                    enqueueImage(id, job, outcome.illustrationTask());
                    notify(id, "ข้อความเสร็จแล้วครับ กำลังสร้างภาพต่อ");
                } else {
                    notify(id, "ตอบคำถามเสร็จแล้วครับ เปิดมินิคุงเพื่ออ่านคำตอบ");
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!shuttingDown && job.state.get() != State.CANCELLED) {
                failChat(id, job, "ยกเลิกการรอคิวแล้ว กรุณาลองใหม่อีกครั้ง");
            }
        } catch (RuntimeException exception) {
            if (shuttingDown) {
                LOGGER.info("process=background_chat event=paused_for_restart job_id={}", id);
                return;
            }
            if (job.state.get() != State.CANCELLED) {
                String error = Objects.requireNonNullElse(exception.getMessage(), "ตอบคำถามไม่สำเร็จ");
                failChat(id, job, error);
            }
        } finally {
            if (job.activeCounted.compareAndSet(true, false)) {
                activeChats.decrementAndGet();
                backgroundSlots.release();
                publishBackgroundMetrics();
            } else if (acquired) {
                backgroundSlots.release();
            }
            job.finished = true;
            expire(id, job);
            restoreMdc(previousMdc);
        }
    }

    private void failChat(UUID id, Job job, String error) {
        if (!compareAndSetChatState(job, State.FAILED)) return;
        job.error = error;
        if (store != null) store.update(id, "FAILED", null, job.error);
        if (metrics != null) metrics.backgroundOutcome("failed");
        notify(id, "ตอบคำถามไม่สำเร็จครับ กลับมาที่มินิคุงเพื่อลองอีกครั้ง");
    }

    private boolean reserveBackgroundQueue(Job job) {
        job.waitingCounted.set(true);
        int queued = queuedChats.incrementAndGet();
        if (queued <= maxBackgroundQueue) {
            publishBackgroundMetrics();
            return true;
        }
        releaseWaiting(job);
        return false;
    }

    private void rejectChat(UUID id, Job job) {
        job.error = BACKGROUND_QUEUE_FULL;
        job.state.set(State.FAILED);
        job.finished = true;
        if (store != null) store.update(id, "FAILED", null, job.error);
        if (metrics != null) metrics.backgroundOutcome("rejected");
        notify(id, BACKGROUND_QUEUE_FULL);
        expire(id, job);
    }

    private void releaseWaiting(Job job) {
        if (job.waitingCounted.compareAndSet(true, false)) {
            queuedChats.decrementAndGet();
            publishBackgroundMetrics();
        }
    }

    private void releaseImageWaiting(Job job) {
        if (job.imageWaitingCounted.compareAndSet(true, false)) {
            queuedImages.decrementAndGet();
            publishImageMetrics();
        }
    }

    private State cancelChat(Job job) {
        if (job.state.compareAndSet(State.QUEUED, State.CANCELLED)) return State.QUEUED;
        if (job.state.compareAndSet(State.RUNNING, State.CANCELLED)) return State.RUNNING;
        return null;
    }

    private ImageState cancelImage(Job job) {
        if (job.imageState.compareAndSet(ImageState.QUEUED, ImageState.CANCELLED)) return ImageState.QUEUED;
        if (job.imageState.compareAndSet(ImageState.RUNNING, ImageState.CANCELLED)) return ImageState.RUNNING;
        return null;
    }

    private boolean compareAndSetChatState(Job job, State target) {
        return job.state.compareAndSet(State.RUNNING, target)
                || job.state.compareAndSet(State.QUEUED, target);
    }

    private void enqueueImage(UUID id, Job job, ChatService.IllustrationTask task) {
        job.imageWaitingCounted.set(true);
        int queued = queuedImages.incrementAndGet();
        if (queued > maxImageQueue) {
            releaseImageWaiting(job);
            failImage(id, job, "คิวสร้างภาพเต็ม กรุณาลองใหม่ภายหลัง", false);
            return;
        }
        job.imageTask = task;
        job.imageState.set(ImageState.QUEUED);
        publishImageMetrics();
        if (store != null) store.updateImage(id, "QUEUED", job.response, "");
        try {
            job.imageRunner = executor.submit(() -> runImage(id, job, task));
            timeoutImageAfter(id, job);
        } catch (RejectedExecutionException exception) {
            failImage(id, job, "ระบบกำลังปิดเพื่อรีสตาร์ต กรุณาลองใหม่อีกครั้ง", false);
        }
    }

    private void runImage(UUID id, Job job, ChatService.IllustrationTask task) {
        boolean acquired = false;
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        MDC.put("job_id", id.toString());
        MDC.put("trace_id", id.toString());
        try {
            if (shuttingDown || job.imageState.get() != ImageState.QUEUED) return;
            long remainingMillis = remainingMillis(job.deadline);
            if (remainingMillis < 1L) {
                failImage(id, job, "image deadline exceeded", false);
                return;
            }
            if (!imageSlots.tryAcquire(remainingMillis, TimeUnit.MILLISECONDS)) {
                failImage(id, job, "image queue deadline exceeded", false);
                return;
            }
            acquired = true;
            releaseImageWaiting(job);
            if (!job.imageState.compareAndSet(ImageState.QUEUED, ImageState.RUNNING)) return;
            job.imageActiveCounted.set(true);
            activeImages.incrementAndGet();
            publishImageMetrics();
            if (store != null) store.updateImage(id, "RUNNING", job.response, "");
            StoryIllustrationService.IllustrationResult result = chatService.completeIllustration(task);
            if (result == null) {
                failImage(id, job, "image worker unavailable", false);
                return;
            }
            if (remainingMillis(job.deadline) < 1L) {
                failImage(id, job, "image deadline exceeded", false);
                return;
            }
            boolean failed = result.attachments().isEmpty();
            if (failed && result.notice().isBlank()) {
                result = new StoryIllustrationService.IllustrationResult(List.of(), StoryIllustrationService.FAILURE_NOTICE);
            }
            ChatCompletionResponse response = mergeIllustration(job.response, result);
            ImageState terminal = failed ? ImageState.FAILED : ImageState.COMPLETED;
            if (!job.imageState.compareAndSet(ImageState.RUNNING, terminal)) return;
            job.response = response;
            job.imageError = failed ? result.notice() : "";
            if (store != null) store.updateImage(id, failed ? "FAILED" : "COMPLETED", response, job.imageError);
            if (metrics != null) metrics.imageOutcome(failed ? "failed" : "completed");
            notify(id, failed ? "สร้างภาพประกอบไม่สำเร็จ แต่ข้อความยังอยู่ครบครับ"
                    : "สร้างภาพประกอบเสร็จแล้วครับ");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (!shuttingDown && job.imageState.get() != ImageState.CANCELLED) {
                failImage(id, job, "ยกเลิกการสร้างภาพแล้ว", true);
            }
        } catch (RuntimeException exception) {
            if (!shuttingDown && job.imageState.get() != ImageState.CANCELLED) failImage(id, job,
                    Objects.requireNonNullElse(exception.getMessage(), "สร้างภาพไม่สำเร็จ"), false);
        } finally {
            if (job.imageActiveCounted.compareAndSet(true, false)) {
                activeImages.decrementAndGet();
                imageSlots.release();
            } else if (acquired) {
                imageSlots.release();
            }
            publishImageMetrics();
            job.imageFinished = true;
            expire(id, job);
            restoreMdc(previousMdc);
        }
    }

    private void failImage(UUID id, Job job, String error, boolean cancelled) {
        releaseImageWaiting(job);
        ImageState target = cancelled ? ImageState.CANCELLED : ImageState.FAILED;
        if (!transitionImage(job, target)) return;
        job.imageError = error;
        if (store != null) store.updateImage(id, cancelled ? "CANCELLED" : "FAILED", job.response, error);
        if (metrics != null) metrics.imageOutcome(cancelled ? "cancelled" : "failed");
        publishImageMetrics();
        notify(id, cancelled ? "ยกเลิกการสร้างภาพแล้วครับ" : "สร้างภาพประกอบไม่สำเร็จ แต่ข้อความยังอยู่ครบครับ");
        if (!job.imageActiveCounted.get()) {
            job.imageFinished = true;
            expire(id, job);
        }
    }

    private boolean transitionImage(Job job, ImageState target) {
        while (true) {
            ImageState current = job.imageState.get();
            if (current == target || current == ImageState.COMPLETED
                    || current == ImageState.FAILED || current == ImageState.CANCELLED) return false;
            if (job.imageState.compareAndSet(current, target)) return true;
        }
    }

    private ChatCompletionResponse mergeIllustration(
            ChatCompletionResponse response, StoryIllustrationService.IllustrationResult illustration) {
        if (response == null) return null;
        List<ChatAttachment> attachments = new ArrayList<>(response.attachments());
        attachments.addAll(illustration.attachments());
        String content = response.choices() == null || response.choices().isEmpty()
                || response.choices().get(0).message() == null
                ? "" : response.choices().get(0).message().content();
        String updatedContent = illustration.appendNoticeTo(content);
        List<ChatCompletionResponse.Choice> choices = response.choices();
        if (choices != null && !choices.isEmpty()) {
            ChatCompletionResponse.Choice first = choices.get(0);
            Message message = first.message() == null ? new Message("assistant", updatedContent)
                    : new Message(first.message().role(), updatedContent);
            choices = new ArrayList<>(choices);
            choices.set(0, new ChatCompletionResponse.Choice(first.index(), message, first.finish_reason()));
        }
        return new ChatCompletionResponse(response.id(), response.object(), response.created(), response.model(),
                choices, response.usage(), List.copyOf(attachments));
    }

    public synchronized boolean resume(UUID id) {
        Job current = jobs.get(id);
        if (current != null && !current.finished) return false;
        if (store == null) return false;
        Optional<BackgroundChatStore.SavedJob> saved = store.find(id);
        if (saved.isEmpty()) return false;
        if ("FAILED".equals(saved.get().status()) || "CANCELLED".equals(saved.get().status())) {
            store.update(id, "QUEUED", null, "");
            store.updateImage(id, "NOT_REQUESTED", null, "");
            if (current != null) jobs.remove(id, current);
            start(saved.get(), false);
            return true;
        }
        if ("FAILED".equals(saved.get().imageStatus()) || "CANCELLED".equals(saved.get().imageStatus())) {
            if (current != null) jobs.remove(id, current);
            startImage(saved.get());
            return true;
        }
        return false;
    }

    @EventListener(ApplicationReadyEvent.class)
    synchronized void resumePending() {
        if (store == null) return;
        store.deleteExpired();
        store.pending().stream().filter(job -> !jobs.containsKey(job.id())).forEach(job -> {
            if ("RUNNING".equals(job.status()) || "QUEUED".equals(job.status())) start(job, true);
            else startImage(job);
        });
    }

    private void start(BackgroundChatStore.SavedJob saved, boolean replay) {
        Job job = new Job();
        job.response = saved.response();
        boolean retry = "FAILED".equals(saved.status()) || "CANCELLED".equals(saved.status());
        boolean missingDeadline = saved.deadlineAt() == null || java.time.Instant.MAX.equals(saved.deadlineAt());
        job.deadline = retry || missingDeadline ? java.time.Instant.now().plus(jobTimeout) : saved.deadlineAt();
        if ((retry || missingDeadline) && store != null) store.reschedule(saved.id(), job.deadline);
        jobs.put(saved.id(), job);
        if (!reserveBackgroundQueue(job)) {
            rejectChat(saved.id(), job);
            return;
        }
        try {
            job.runner = executor.submit(() -> run(saved.id(), job, saved.request(),
                    new ConversationId(saved.conversationId()), replay));
        } catch (RejectedExecutionException exception) {
            releaseWaiting(job);
            failChat(saved.id(), job, "ระบบกำลังปิดเพื่อรีสตาร์ต กรุณาลองใหม่อีกครั้ง");
            return;
        }
        timeoutAfter(saved.id(), job);
    }

    private void startImage(BackgroundChatStore.SavedJob saved) {
        if (saved.response() == null) return;
        Job job = new Job();
        job.response = saved.response();
        boolean retry = "FAILED".equals(saved.imageStatus()) || "CANCELLED".equals(saved.imageStatus());
        boolean missingDeadline = saved.deadlineAt() == null || java.time.Instant.MAX.equals(saved.deadlineAt());
        job.deadline = retry || missingDeadline ? java.time.Instant.now().plus(jobTimeout) : saved.deadlineAt();
        if ((retry || missingDeadline) && store != null) store.reschedule(saved.id(), job.deadline);
        job.state.set(State.COMPLETED);
        job.imageState.set(ImageState.QUEUED);
        jobs.put(saved.id(), job);
        enqueueImage(saved.id(), job, illustrationTask(saved));
    }

    private ChatService.IllustrationTask illustrationTask(BackgroundChatStore.SavedJob saved) {
        String userMessage = saved.request() == null || saved.request().messages() == null
                ? "" : saved.request().messages().stream()
                        .filter(Objects::nonNull)
                        .reduce((first, second) -> second)
                        .map(Message::content).orElse("");
        String assistant = saved.response().choices() == null || saved.response().choices().isEmpty()
                || saved.response().choices().get(0).message() == null
                ? "" : saved.response().choices().get(0).message().content();
        String owner = saved.ownerId() == null || saved.ownerId().isBlank()
                ? (saved.request() == null ? "" : saved.request().owner_id()) : saved.ownerId();
        return new ChatService.IllustrationTask(owner, saved.conversationId(), userMessage, assistant,
                true, saved.response().id(), saved.deadlineAt());
    }

    private View view(BackgroundChatStore.SavedJob saved) {
        return new View(saved.id(), saved.status().toLowerCase(java.util.Locale.ROOT), saved.response(), saved.error(),
                saved.imageStatus().toLowerCase(java.util.Locale.ROOT), saved.imageError());
    }

    private boolean imagePending(Job job) {
        return job.imageState.get() == ImageState.QUEUED || job.imageState.get() == ImageState.RUNNING;
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
        long delayMillis = remainingMillis(job.deadline);
        if (delayMillis == Long.MAX_VALUE) delayMillis = jobTimeout.toMillis();
        final long timeoutMillis = delayMillis;
        timeoutExecutor.schedule(() -> {
            State previous = null;
            if (job.state.compareAndSet(State.QUEUED, State.FAILED)) previous = State.QUEUED;
            else if (job.state.compareAndSet(State.RUNNING, State.FAILED)) previous = State.RUNNING;
            if (previous == null) return;
            if (previous == State.QUEUED) {
                releaseWaiting(job);
                job.finished = true;
            }
            job.error = "ตอบคำถามใช้เวลานานเกินไป กรุณาลองใหม่อีกครั้ง";
            if (job.runner != null) job.runner.cancel(true);
            if (store != null) store.update(id, "FAILED", null, job.error);
            if (metrics != null) metrics.backgroundOutcome("timeout");
            LOGGER.warn("process=background_chat event=timeout job_id={} timeout_ms={}",
                    id, timeoutMillis);
            notify(id, "ตอบคำถามใช้เวลานานเกินไปครับ กลับมาที่มินิคุงเพื่อลองใหม่อีกครั้ง");
            expire(id, job);
        }, Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS);
    }

    private void timeoutImageAfter(UUID id, Job job) {
        long delayMillis = remainingMillis(job.deadline);
        if (delayMillis == Long.MAX_VALUE) return;
        timeoutExecutor.schedule(() -> {
            if (!imagePending(job)) return;
            failImage(id, job, "สร้างภาพใช้เวลานานเกินไป กรุณาลองใหม่อีกครั้ง", false);
            if (job.imageRunner != null) job.imageRunner.cancel(true);
        }, Math.max(1L, delayMillis), TimeUnit.MILLISECONDS);
    }

    private void restoreMdc(Map<String, String> previous) {
        try {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        } catch (RuntimeException ignored) { }
    }

    private void publishImageMetrics() {
        if (metrics != null) metrics.imageQueue(queuedImages.get(), activeImages.get());
    }

    private void publishBackgroundMetrics() {
        if (metrics != null) metrics.backgroundQueue(queuedChats.get(), activeChats.get());
    }

    private long remainingMillis(java.time.Instant deadline) {
        if (deadline == null || java.time.Instant.MAX.equals(deadline)) return Long.MAX_VALUE;
        try {
            return Math.max(0L, java.time.Duration.between(java.time.Instant.now(), deadline).toMillis());
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void close() {
        shuttingDown = true;
        timeoutExecutor.shutdownNow();
        executor.shutdownNow();
    }

    public record View(UUID id, String status, ChatCompletionResponse response, String error,
            String imageStatus, String imageError) {
        public View(UUID id, String status, ChatCompletionResponse response, String error) {
            this(id, status, response, error, "not_requested", "");
        }
    }

    private enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, REVIEW_REQUIRED }
    private enum ImageState { NOT_REQUESTED, QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    private static final class Job {
        private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
        private final AtomicReference<ImageState> imageState = new AtomicReference<>(ImageState.NOT_REQUESTED);
        private final AtomicBoolean waitingCounted = new AtomicBoolean();
        private final AtomicBoolean activeCounted = new AtomicBoolean();
        private final AtomicBoolean imageWaitingCounted = new AtomicBoolean();
        private final AtomicBoolean imageActiveCounted = new AtomicBoolean();
        private volatile ChatCompletionResponse response;
        private volatile boolean finished;
        private volatile boolean imageFinished;
        private volatile String error = "";
        private volatile String imageError = "";
        private volatile Future<?> runner;
        private volatile Future<?> imageRunner;
        private volatile ChatService.IllustrationTask imageTask;
        private volatile java.time.Instant deadline;

        private View view(UUID id) {
            return new View(id, state.get().name().toLowerCase(java.util.Locale.ROOT), response, error,
                    imageState.get().name().toLowerCase(java.util.Locale.ROOT), imageError);
        }
    }
}
