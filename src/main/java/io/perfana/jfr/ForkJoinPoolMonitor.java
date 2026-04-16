/*
 * Copyright (C) 2023 Peter Paul Bakker - Perfana
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.perfana.jfr;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ForkJoinPoolMonitor implements AutoCloseable {

    static final String MEASUREMENT_NAME = "forkjoin-pool";

    private static final Logger log = Logger.getLogger(ForkJoinPoolMonitor.class);
    private static final Duration DEFAULT_SAMPLING_INTERVAL = Duration.ofSeconds(1);

    private final JfrEventProcessor eventProcessor;
    private final ForkJoinPool pool;
    private final Map<String, String> tags;
    private final Duration samplingInterval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public ForkJoinPoolMonitor(JfrEventProcessor eventProcessor) {
        this(
                eventProcessor,
                ForkJoinPool.commonPool(),
                Map.of("pool", "common"),
                DEFAULT_SAMPLING_INTERVAL,
                createScheduler());
    }

    ForkJoinPoolMonitor(JfrEventProcessor eventProcessor,
                        ForkJoinPool pool,
                        Map<String, String> tags,
                        Duration samplingInterval,
                        ScheduledExecutorService scheduler) {
        this.eventProcessor = Objects.requireNonNull(eventProcessor, "eventProcessor must not be null");
        this.pool = Objects.requireNonNull(pool, "pool must not be null");
        this.tags = Objects.requireNonNull(tags, "tags must not be null");
        this.samplingInterval = Objects.requireNonNull(samplingInterval, "samplingInterval must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");

        if (samplingInterval.isZero() || samplingInterval.isNegative()) {
            throw new IllegalArgumentException("samplingInterval must be positive");
        }
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::sampleSafely, 0, samplingInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void sample() {
        Map<String, Object> extraFields = Map.of(
                "poolSize", pool.getPoolSize(),
                "queuedTaskCount", pool.getQueuedTaskCount()
        );

        eventProcessor.processEvent(ProcessedJfrEvent.of(
                Instant.now(),
                MEASUREMENT_NAME,
                tags,
                "activeThreadCount",
                pool.getActiveThreadCount(),
                extraFields
        ));
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void sampleSafely() {
        try {
            sample();
        } catch (RuntimeException e) {
            log.error("Failed to sample ForkJoin common pool metrics: %s", e.getMessage());
        }
    }

    private static ScheduledExecutorService createScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("jfr-exporter-forkjoin");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }
}
