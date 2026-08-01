package com.minikun.agent.minikun_agent.api.openai;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class ChatTransactionLogger {

    private static final ZoneOffset LOG_OFFSET = ZoneOffset.ofHours(7);
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final AtomicLong sequence = new AtomicLong();

    public Transaction start(String requestId, String model, boolean stream, int messageCount) {
        return new Transaction(requestId, model, stream, messageCount, Instant.now(), sequence.incrementAndGet());
    }

    public final class Transaction {
        private final String requestId;
        private final String model;
        private final boolean stream;
        private final int messageCount;
        private final Instant startedAt;
        @SuppressWarnings("unused")
        private final long sequence;

        private Transaction(String requestId, String model, boolean stream, int messageCount,
                Instant startedAt, long sequence) {
            this.requestId = requestId;
            this.model = model;
            this.stream = stream;
            this.messageCount = messageCount;
            this.startedAt = startedAt;
            this.sequence = sequence;
        }

        public String requestId() {
            return requestId;
        }

        public String model() {
            return model;
        }

        public boolean stream() {
            return stream;
        }

        public int messageCount() {
            return messageCount;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public void success() {
            write("success", null);
        }

        public void failed(Throwable exception) {
            write("error", exception.getClass().getSimpleName());
        }

        public void cancelled() {
            write("cancelled", null);
        }

        private void write(String status, String detail) {
            OffsetDateTime now = OffsetDateTime.now(LOG_OFFSET);
            long durationMillis = Duration.between(startedAt, now.toInstant()).toMillis();
            log.info("time={} id={} model={} stream={} messages={} status={} duration_ms={}{}",
                    now.format(LOG_TIME_FORMAT), requestId, model, stream, messageCount,
                    status, durationMillis, detail == null ? "" : " detail=" + detail);
        }
    }
}