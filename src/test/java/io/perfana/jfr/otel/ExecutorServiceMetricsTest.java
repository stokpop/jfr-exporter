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
package io.perfana.jfr.otel;

import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutorServiceMetricsTest {

    @Test
    void registersThreeGaugesForThreadPoolExecutor() throws Exception {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            ExecutorServiceMetrics.register(provider, executor, "test-pool");

            assertTrue(provider.hasGauge("jfr.executor.threads.active"));
            assertTrue(provider.hasGauge("jfr.executor.threads.total"));
            assertTrue(provider.hasGauge("jfr.executor.queue.size"));
            assertEquals(3, provider.gaugeCount());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void gaugesReflectCurrentPoolSize() throws Exception {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            ExecutorServiceMetrics.register(provider, executor, "pool-a");

            // No tasks submitted yet — pool has not created threads
            assertEquals(0L, provider.readGauge("jfr.executor.threads.total"));
            assertEquals(0L, provider.readGauge("jfr.executor.threads.active"));
            assertEquals(0L, provider.readGauge("jfr.executor.queue.size"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void gaugesReflectActiveAndQueuedTasksDuringExecution() throws Exception {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch taskRunning = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);
        try {
            ExecutorServiceMetrics.register(provider, executor, "pool-b");

            executor.submit(() -> {
                taskRunning.countDown();
                try { releaseTask.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            // Queue a second task that will wait (pool size is 1)
            executor.submit(() -> {});

            assertTrue(taskRunning.await(5, TimeUnit.SECONDS));

            assertEquals(1L, provider.readGauge("jfr.executor.threads.active"));
            assertTrue(provider.readGauge("jfr.executor.queue.size") >= 1L,
                    "Expected at least one task in the queue");
        } finally {
            releaseTask.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void returnsNoOpForNonThreadPoolExecutor() {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        // Virtual-thread executor is not a ThreadPoolExecutor
        ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            ExecutorServiceMetrics metrics = ExecutorServiceMetrics.register(
                    provider, virtualThreadExecutor, "vt-pool");

            assertEquals(0, provider.gaugeCount(),
                    "No gauges should be registered for non-ThreadPoolExecutor");
        } finally {
            virtualThreadExecutor.shutdown();
        }
    }

    @Test
    void closingHandlesDeregistersGauges() throws Exception {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            ExecutorServiceMetrics metrics = ExecutorServiceMetrics.register(
                    provider, executor, "pool-c");
            assertEquals(3, provider.gaugeCount());

            metrics.close();

            assertEquals(3, provider.closedCount(), "All three gauge handles should be closed");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void allowsAttributesOverloadForCustomLabels() {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Attributes attrs = Attributes.empty();
            ExecutorServiceMetrics.register(provider, executor, attrs);
            assertEquals(3, provider.gaugeCount());
        } finally {
            executor.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final class FakeGaugeMeterProvider implements JfrMeterProvider {
        private final Map<String, LongSupplier> gauges = new HashMap<>();
        private int closedCount = 0;

        @Override
        public LongValueRecorder registerLongValueRecorder(MetricDescriptor metricDescriptor) {
            return (value, attrs) -> {};
        }

        @Override
        public DoubleValueRecorder registerDoubleValueRecorder(MetricDescriptor metricDescriptor) {
            return (value, attrs) -> {};
        }

        @Override
        public AutoCloseable registerLongGauge(MetricDescriptor descriptor,
                                               Attributes attributes,
                                               LongSupplier supplier) {
            gauges.put(descriptor.name(), supplier);
            return () -> closedCount++;
        }

        @Override
        public void close() {}

        boolean hasGauge(String name) { return gauges.containsKey(name); }
        int gaugeCount()              { return gauges.size(); }
        int closedCount()             { return closedCount; }

        long readGauge(String name) {
            LongSupplier s = gauges.get(name);
            if (s == null) throw new AssertionError("No gauge registered for: " + name);
            return s.getAsLong();
        }
    }

    private record RecordedGauge(String name, LongSupplier supplier) {}
}
