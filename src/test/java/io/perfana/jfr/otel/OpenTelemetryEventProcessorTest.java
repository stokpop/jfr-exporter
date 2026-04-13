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
import io.perfana.jfr.ProcessedJfrEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryEventProcessorTest {

    @Test
    void recordsNumericMetricsAndSkipsStacktracePayload() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(
                meterProvider,
                Map.of("service", "checkout"));

        ProcessedJfrEvent event = ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:00Z"),
                "CPU",
                "machineTotal",
                72.5d,
                Map.of(
                        "jvmUser", 22.5d,
                        "jvmSystem", 10.0d,
                        "thread", "JFR Event Stream"),
                List.of("com.example.Allocator.allocate(Allocator.java:10)")
        );

        eventProcessor.processEvent(event);

        assertTrue(meterProvider.doubleRecords.containsKey("jfr.CPU.machineTotal"));
        assertTrue(meterProvider.doubleRecords.containsKey("jfr.CPU.jvmUser"));
        assertTrue(meterProvider.doubleRecords.containsKey("jfr.CPU.jvmSystem"));

        RecordedDoubleValue machineTotal = meterProvider.doubleRecords.get("jfr.CPU.machineTotal").get(0);
        assertEquals(72.5d, machineTotal.value());
        assertEquals("checkout", machineTotal.attributes().get(AttributeKey.stringKey("service")));
        assertEquals("JFR Event Stream", machineTotal.attributes().get(AttributeKey.stringKey("thread")));
        assertFalse(machineTotal.attributes().asMap().containsKey(AttributeKey.stringKey("stacktrace")));
        assertEquals(1, meterProvider.doubleRegistrationCount("jfr.CPU.machineTotal"));
        assertEquals(1, meterProvider.doubleRegistrationCount("jfr.CPU.jvmUser"));
        assertEquals(1, meterProvider.doubleRegistrationCount("jfr.CPU.jvmSystem"));
    }

    @Test
    void recordsExampleAllocationValueRecorder() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(
                meterProvider,
                Map.of("service", "checkout"));

        eventProcessor.processEvent(ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:01Z"),
                "big-allocations",
                "bytes",
                8192L,
                Map.of("objectClass", "byte[]", "thread", "main"),
                List.of("com.example.Allocator.allocate(Allocator.java:10)")
        ));

        assertTrue(meterProvider.longRecords.containsKey("jfr.big-allocations.bytes"));
        assertTrue(meterProvider.longRecords.containsKey("jfr.object.allocation.bytes"));
        assertEquals(8192L, meterProvider.longRecords.get("jfr.object.allocation.bytes").get(0).value());
    }

    @Test
    void recordsNumericExtraFieldsAsMetricsByDefault() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(meterProvider, Map.of());

        eventProcessor.processEvent(ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:02Z"),
                "custom-measurement",
                "duration-ms",
                25.0d,
                Map.of(
                        "queueDepth", 7L,
                        "phase", "steady-state")
        ));

        assertTrue(meterProvider.longRecords.containsKey("jfr.custom-measurement.queueDepth"));
        assertEquals(7L, meterProvider.longRecords.get("jfr.custom-measurement.queueDepth").get(0).value());

        RecordedDoubleValue duration = meterProvider.doubleRecords.get("jfr.custom-measurement.duration-ms").get(0);
        assertEquals("steady-state", duration.attributes().get(AttributeKey.stringKey("phase")));
        assertFalse(duration.attributes().asMap().containsKey(AttributeKey.longKey("queueDepth")));
    }

    @Test
    void keepsNumericIdentifiersAsAttributes() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(meterProvider, Map.of());

        eventProcessor.processEvent(ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:03Z"),
                "virtual-thread-submit-failed",
                "count",
                1L,
                Map.of(
                        "javaThreadId", 42L,
                        "exceptionMessage", "scheduler queue full")
        ));

        assertFalse(meterProvider.longRecords.containsKey("jfr.virtual-thread-submit-failed.javaThreadId"));

        RecordedLongValue count = meterProvider.longRecords.get("jfr.virtual-thread-submit-failed.count").get(0);
        assertEquals(42L, count.attributes().get(AttributeKey.longKey("javaThreadId")));
        assertEquals("scheduler queue full", count.attributes().get(AttributeKey.stringKey("exceptionMessage")));
    }

    @Test
    void recordsNumericTimeoutAsMetricByDefault() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(meterProvider, Map.of());

        Map<String, Object> extraFields = new LinkedHashMap<>();
        extraFields.put("timeout", 5000L);
        extraFields.put("monitor-class", "java.lang.Object");
        extraFields.put("thread", "main");

        eventProcessor.processEvent(ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:04Z"),
                "java-monitor-wait",
                "duration-ns",
                125_000L,
                extraFields,
                List.of("java.lang.Object.wait(Object.java:-1)")
        ));

        assertTrue(meterProvider.longRecords.containsKey("jfr.java-monitor-wait.timeout"));
        assertEquals(5000L, meterProvider.longRecords.get("jfr.java-monitor-wait.timeout").get(0).value());

        RecordedLongValue duration = meterProvider.longRecords.get("jfr.java-monitor-wait.duration-ns").get(0);
        assertFalse(duration.attributes().asMap().containsKey(AttributeKey.longKey("timeout")));
        assertEquals("java.lang.Object", duration.attributes().get(AttributeKey.stringKey("monitor-class")));
        assertEquals("main", duration.attributes().get(AttributeKey.stringKey("thread")));
    }

    @Test
    void mergesEventTagsOverridesDefaultsAndSkipsNullAttributes() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(
                meterProvider,
                Map.of("service", "checkout", "environment", "perf"));

        Map<String, Object> extraFields = new LinkedHashMap<>();
        extraFields.put("phase", "load");
        extraFields.put("enabled", true);
        extraFields.put("optional", null);

        ProcessedJfrEvent event = new ProcessedJfrEvent(
                Instant.parse("2026-04-11T00:00:05Z"),
                "socket-write-rate-bytes",
                Map.of("service", "payments", "pod", "checkout-1"),
                "bytesWritten",
                4096L,
                extraFields,
                List.of("com.example.SocketWriter.flush(SocketWriter.java:42)")
        );

        eventProcessor.processEvent(event);

        RecordedLongValue bytesWritten = meterProvider.longRecords.get("jfr.socket-write-rate-bytes.bytesWritten").get(0);
        assertEquals("payments", bytesWritten.attributes().get(AttributeKey.stringKey("service")));
        assertEquals("perf", bytesWritten.attributes().get(AttributeKey.stringKey("environment")));
        assertEquals("checkout-1", bytesWritten.attributes().get(AttributeKey.stringKey("pod")));
        assertEquals("load", bytesWritten.attributes().get(AttributeKey.stringKey("phase")));
        assertEquals(Boolean.TRUE, bytesWritten.attributes().get(AttributeKey.booleanKey("enabled")));
        assertFalse(bytesWritten.attributes().asMap().containsKey(AttributeKey.stringKey("optional")));
    }

    @Test
    void recordsFloatingPointExtraFieldsAsDoubleMetrics() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(meterProvider, Map.of());

        Map<String, Object> extraFields = new LinkedHashMap<>();
        extraFields.put("cpuRatio", 0.75f);
        extraFields.put("thread", "main");

        eventProcessor.processEvent(ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:06Z"),
                "container-cpu-load",
                "machineTotal",
                91.0d,
                extraFields
        ));

        RecordedDoubleValue cpuRatio = meterProvider.doubleRecords.get("jfr.container-cpu-load.cpuRatio").get(0);
        assertEquals(0.75d, cpuRatio.value());
        assertEquals("main", cpuRatio.attributes().get(AttributeKey.stringKey("thread")));
        assertFalse(cpuRatio.attributes().asMap().containsKey(AttributeKey.doubleKey("cpuRatio")));
    }

    @Test
    void reusesRegisteredRecordersForRepeatedMetricMappings() {
        FakeMeterProvider meterProvider = new FakeMeterProvider();
        OpenTelemetryEventProcessor eventProcessor = new OpenTelemetryEventProcessor(meterProvider, Map.of());

        ProcessedJfrEvent first = ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:07Z"),
                "threads",
                "activeCount",
                12L,
                Map.of("daemonCount", 11L)
        );
        ProcessedJfrEvent second = ProcessedJfrEvent.of(
                Instant.parse("2026-04-11T00:00:08Z"),
                "threads",
                "activeCount",
                13L,
                Map.of("daemonCount", 12L)
        );

        eventProcessor.processEvent(first);
        eventProcessor.processEvent(second);

        assertEquals(1, meterProvider.longRegistrationCount("jfr.threads.activeCount"));
        assertEquals(1, meterProvider.longRegistrationCount("jfr.threads.daemonCount"));
        assertEquals(2, meterProvider.longRecords.get("jfr.threads.activeCount").size());
        assertEquals(2, meterProvider.longRecords.get("jfr.threads.daemonCount").size());
    }

    private static final class FakeMeterProvider implements JfrMeterProvider {
        private final Map<String, List<RecordedLongValue>> longRecords = new HashMap<>();
        private final Map<String, List<RecordedDoubleValue>> doubleRecords = new HashMap<>();
        private final Map<String, Integer> longRegistrations = new HashMap<>();
        private final Map<String, Integer> doubleRegistrations = new HashMap<>();
        private final Map<String, LongValueRecorder> longRecorders = new HashMap<>();
        private final Map<String, DoubleValueRecorder> doubleRecorders = new HashMap<>();

        @Override
        public LongValueRecorder registerLongValueRecorder(MetricDescriptor metricDescriptor) {
            longRegistrations.merge(metricDescriptor.name(), 1, Integer::sum);
            return longRecorders.computeIfAbsent(metricDescriptor.name(), recorderName -> (value, attributes) -> longRecords
                    .computeIfAbsent(recorderName, metricName -> new ArrayList<>())
                    .add(new RecordedLongValue(value, attributes)));
        }

        @Override
        public DoubleValueRecorder registerDoubleValueRecorder(MetricDescriptor metricDescriptor) {
            doubleRegistrations.merge(metricDescriptor.name(), 1, Integer::sum);
            return doubleRecorders.computeIfAbsent(metricDescriptor.name(), recorderName -> (value, attributes) -> doubleRecords
                    .computeIfAbsent(recorderName, metricName -> new ArrayList<>())
                    .add(new RecordedDoubleValue(value, attributes)));
        }

        @Override
        public AutoCloseable registerLongGauge(MetricDescriptor descriptor, Attributes attributes, LongSupplier supplier) {
            return () -> {};
        }

        @Override
        public void close() {
        }

        private int longRegistrationCount(String metricName) {
            return longRegistrations.getOrDefault(metricName, 0);
        }

        private int doubleRegistrationCount(String metricName) {
            return doubleRegistrations.getOrDefault(metricName, 0);
        }
    }

    private record RecordedLongValue(long value, Attributes attributes) {
    }

    private record RecordedDoubleValue(double value, Attributes attributes) {
    }
}
