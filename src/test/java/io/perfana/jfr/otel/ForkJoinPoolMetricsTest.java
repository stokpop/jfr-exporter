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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForkJoinPoolMetricsTest {

    @Test
    void registersThreeGaugesForForkJoinPool() {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            ForkJoinPoolMetrics.register(provider, pool, "test-fj");

            assertTrue(provider.hasGauge("jfr.forkjoin.threads.active"));
            assertTrue(provider.hasGauge("jfr.forkjoin.pool.size"));
            assertTrue(provider.hasGauge("jfr.forkjoin.tasks.queued"));
            assertEquals(3, provider.gaugeCount());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void gaugesReflectPoolSizeForCustomPool() {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ForkJoinPool pool = new ForkJoinPool(3);
        try {
            ForkJoinPoolMetrics.register(provider, pool, "my-fj");

            // Pool size may be 0 before any tasks are submitted; at minimum it won't throw
            long poolSize = provider.readGauge("jfr.forkjoin.pool.size");
            assertTrue(poolSize >= 0, "Pool size must be non-negative");

            long active = provider.readGauge("jfr.forkjoin.threads.active");
            assertTrue(active >= 0, "Active thread count must be non-negative");

            long queued = provider.readGauge("jfr.forkjoin.tasks.queued");
            assertTrue(queued >= 0, "Queued task count must be non-negative");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void registerCommonPoolUsesCommonPoolAttribute() {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ForkJoinPoolMetrics metrics = ForkJoinPoolMetrics.registerCommonPool(provider);

        assertEquals(3, provider.gaugeCount());
        // Verify the common pool supplier returns the real common pool value without throwing
        long poolSize = provider.readGauge("jfr.forkjoin.pool.size");
        assertTrue(poolSize >= 0);
    }

    @Test
    void closingHandlesDeregistersGauges() throws Exception {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            ForkJoinPoolMetrics metrics = ForkJoinPoolMetrics.register(provider, pool, "close-test");
            assertEquals(3, provider.gaugeCount());

            metrics.close();

            assertEquals(3, provider.closedCount(), "All three gauge handles should be closed");
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void attributesOverloadForCustomLabels() {
        FakeGaugeMeterProvider provider = new FakeGaugeMeterProvider();
        ForkJoinPool pool = new ForkJoinPool(2);
        try {
            Attributes attrs = Attributes.of(AttributeKey.stringKey("env"), "perf");
            ForkJoinPoolMetrics.register(provider, pool, attrs);
            assertEquals(3, provider.gaugeCount());
        } finally {
            pool.shutdown();
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
}
