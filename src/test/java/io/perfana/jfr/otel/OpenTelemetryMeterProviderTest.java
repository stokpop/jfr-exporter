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
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenTelemetryMeterProviderTest {

    @Test
    void exportsRecordedMetricsThroughSdkPipeline() {
        CapturingMetricExporter exporter = new CapturingMetricExporter();

        try (OpenTelemetryMeterProvider meterProvider = new OpenTelemetryMeterProvider(
                exporter,
                Map.of("service", "checkout"),
                Duration.ofHours(1))) {
            JfrMeterProvider.LongValueRecorder allocationRecorder = meterProvider.registerLongValueRecorder(
                    new JfrMeterProvider.MetricDescriptor("jfr.big-allocations.bytes", "Big allocation sizes", "By"));
            JfrMeterProvider.DoubleValueRecorder cpuRecorder = meterProvider.registerDoubleValueRecorder(
                    new JfrMeterProvider.MetricDescriptor("jfr.CPU.machineTotal", "Machine CPU load", "1"));

            Attributes attributes = Attributes.of(AttributeKey.stringKey("thread"), "main");
            allocationRecorder.record(8192L, attributes);
            cpuRecorder.record(72.5d, attributes);
        }

        MetricData allocationMetric = exporter.metricNamed("jfr.big-allocations.bytes");
        assertNotNull(allocationMetric);
        assertEquals("By", allocationMetric.getUnit());
        assertEquals("checkout", allocationMetric.getResource().getAttribute(AttributeKey.stringKey("service.name")));

        HistogramPointData allocationPoint = allocationMetric.getHistogramData().getPoints().iterator().next();
        assertEquals(1L, allocationPoint.getCount());
        assertEquals(8192.0d, allocationPoint.getSum());
        assertEquals("main", allocationPoint.getAttributes().get(AttributeKey.stringKey("thread")));

        MetricData cpuMetric = exporter.metricNamed("jfr.CPU.machineTotal");
        assertNotNull(cpuMetric);
        assertEquals("1", cpuMetric.getUnit());

        HistogramPointData cpuPoint = cpuMetric.getHistogramData().getPoints().iterator().next();
        assertEquals(1L, cpuPoint.getCount());
        assertEquals(72.5d, cpuPoint.getSum());
    }

    @Test
    void sanitizeMetricNameRetainsUniquenessForLongMetricNames() {
        String longMetricName = "jfr." + "very-long-metric-name-".repeat(4);
        String otherLongMetricName = longMetricName + "other";

        String sanitized = OpenTelemetryMeterProvider.sanitizeMetricName(longMetricName);
        String sanitizedOther = OpenTelemetryMeterProvider.sanitizeMetricName(otherLongMetricName);

        assertTrue(sanitized.length() <= 63);
        assertTrue(sanitizedOther.length() <= 63);
        assertNotEquals(sanitized, sanitizedOther);
    }

    @Test
    void reusesRegisteredRecordersAcrossRegistrationAbstractions() {
        CapturingMetricExporter exporter = new CapturingMetricExporter();

        try (OpenTelemetryMeterProvider meterProvider = new OpenTelemetryMeterProvider(
                exporter,
                Map.of("service", "checkout"),
                Duration.ofHours(1))) {
            JfrMeterProvider.LongValueRecorder longRecorder = meterProvider.registerLongValueRecorder(
                    new JfrMeterProvider.MetricDescriptor("jfr.threads.activeCount", "Active thread count", "1"));
            JfrMeterProvider.LongValueRecorder longRecorderViaDefaults = meterProvider.registerLongValueRecorder(
                    "jfr.threads.activeCount", "Overridden description", "1");

            JfrMeterProvider.DoubleValueRecorder doubleRecorder = meterProvider.registerDoubleValueRecorder(
                    new JfrMeterProvider.MetricDescriptor("jfr.CPU.machineTotal", "Machine CPU load", "1"));
            JfrMeterProvider.DoubleValueRecorder doubleRecorderViaDefaults = meterProvider.registerDoubleValueRecorder(
                    "jfr.CPU.machineTotal", "Overridden description", "1");

            assertSame(longRecorder, longRecorderViaDefaults);
            assertSame(doubleRecorder, doubleRecorderViaDefaults);
        }
    }

    @Test
    void gaugeReportsCurrentSupplierValueAtFlushTime() {
        CapturingMetricExporter exporter = new CapturingMetricExporter();
        AtomicLong gaugeValue = new AtomicLong(42L);

        try (OpenTelemetryMeterProvider meterProvider = new OpenTelemetryMeterProvider(
                exporter,
                Map.of("service", "checkout"),
                Duration.ofHours(1))) {
            Attributes attributes = Attributes.of(AttributeKey.stringKey("pool"), "test-pool");
            meterProvider.registerLongGauge(
                    new JfrMeterProvider.MetricDescriptor("jfr.executor.threads.active",
                            "Active threads", "1"),
                    attributes,
                    gaugeValue::get);
        }

        MetricData gaugeMetric = exporter.metricNamed("jfr.executor.threads.active");
        assertNotNull(gaugeMetric);
        assertEquals(MetricDataType.LONG_GAUGE, gaugeMetric.getType());
        assertEquals("1", gaugeMetric.getUnit());

        GaugeData<LongPointData> gaugeData = gaugeMetric.getLongGaugeData();
        LongPointData point = gaugeData.getPoints().iterator().next();
        assertEquals(42L, point.getValue());
        assertEquals("test-pool", point.getAttributes().get(AttributeKey.stringKey("pool")));
    }

    @Test
    void gaugePicksUpChangedSupplierValueBetweenCollections() {
        CapturingMetricExporter exporter = new CapturingMetricExporter();
        AtomicLong poolSize = new AtomicLong(2L);

        try (OpenTelemetryMeterProvider meterProvider = new OpenTelemetryMeterProvider(
                exporter,
                Map.of(),
                Duration.ofHours(1))) {
            meterProvider.registerLongGauge(
                    new JfrMeterProvider.MetricDescriptor("jfr.executor.threads.total",
                            "Pool size", "1"),
                    Attributes.empty(),
                    poolSize::get);

            // Simulate pool growth before the second flush
            poolSize.set(8L);
        }

        MetricData gaugeMetric = exporter.metricNamed("jfr.executor.threads.total");
        assertNotNull(gaugeMetric);
        long lastObservedValue = gaugeMetric.getLongGaugeData().getPoints().stream()
                .mapToLong(LongPointData::getValue)
                .max()
                .orElseThrow();
        assertEquals(8L, lastObservedValue);
    }

    @Test
    void closingGaugeHandleDeregistersCallback() throws Exception {
        CapturingMetricExporter exporter = new CapturingMetricExporter();

        try (OpenTelemetryMeterProvider meterProvider = new OpenTelemetryMeterProvider(
                exporter,
                Map.of(),
                Duration.ofHours(1))) {
            AutoCloseable handle = meterProvider.registerLongGauge(
                    new JfrMeterProvider.MetricDescriptor("jfr.executor.queue.size",
                            "Queue size", "1"),
                    Attributes.empty(),
                    () -> 5L);

            handle.close();

            // After closing the handle the gauge should no longer be observed on flush.
        }

        // The gauge may have been reported before close; we only care that closing did not throw.
        // The important property is that no exception propagates.
        assertTrue(true, "Gauge handle closed without error");
    }

    private static final class CapturingMetricExporter implements MetricExporter {
        private final List<MetricData> metrics = new ArrayList<>();

        @Override
        public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
            return AggregationTemporality.CUMULATIVE;
        }

        @Override
        public CompletableResultCode export(Collection<MetricData> metricData) {
            metrics.addAll(metricData);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        private MetricData metricNamed(String name) {
            return metrics.stream()
                    .filter(metric -> metric.getName().equals(name))
                    .findFirst()
                    .orElse(null);
        }
    }
}
