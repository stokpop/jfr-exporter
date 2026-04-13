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
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.perfana.jfr.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class OpenTelemetryMeterProvider implements JfrMeterProvider {

    private static final Logger log = Logger.getLogger(OpenTelemetryMeterProvider.class);
    private static final String SCOPE_NAME = "io.perfana.jfr";
    private static final String DEFAULT_SERVICE_NAME = "jfr-exporter";
    private static final Duration METRIC_READER_INTERVAL = Duration.ofSeconds(1);

    private final SdkMeterProvider meterProvider;
    private final Meter meter;
    private final Map<String, LongValueRecorder> longRecorderCache = new ConcurrentHashMap<>();
    private final Map<String, DoubleValueRecorder> doubleRecorderCache = new ConcurrentHashMap<>();

    public OpenTelemetryMeterProvider(String endpoint, Map<String, String> tags) {
        this(createMetricExporter(validateEndpoint(endpoint)), tags, METRIC_READER_INTERVAL);
        log.info("OpenTelemetry metrics exporter configured for %s", endpoint);
    }

    OpenTelemetryMeterProvider(MetricExporter metricExporter, Map<String, String> tags, Duration readerInterval) {
        Objects.requireNonNull(metricExporter, "metricExporter must not be null");
        Objects.requireNonNull(readerInterval, "readerInterval must not be null");
        if (readerInterval.isZero() || readerInterval.isNegative()) {
            throw new IllegalArgumentException("readerInterval must be positive");
        }

        Map<String, String> safeTags = tags == null ? Map.of() : Map.copyOf(tags);
        this.meterProvider = SdkMeterProvider.builder()
                .setResource(createResource(safeTags))
                .registerMetricReader(PeriodicMetricReader.builder(
                                metricExporter)
                        .setInterval(readerInterval)
                        .build())
                .build();
        this.meter = meterProvider.get(SCOPE_NAME);
    }

    @Override
    public LongValueRecorder registerLongValueRecorder(MetricDescriptor metricDescriptor) {
        MetricDescriptor descriptor = Objects.requireNonNull(metricDescriptor, "metricDescriptor must not be null");
        return longRecorderCache.computeIfAbsent(instrumentKey(descriptor), ignored -> createLongValueRecorder(descriptor));
    }

    @Override
    public DoubleValueRecorder registerDoubleValueRecorder(MetricDescriptor metricDescriptor) {
        MetricDescriptor descriptor = Objects.requireNonNull(metricDescriptor, "metricDescriptor must not be null");
        return doubleRecorderCache.computeIfAbsent(instrumentKey(descriptor), ignored -> createDoubleValueRecorder(descriptor));
    }

    private LongValueRecorder createLongValueRecorder(MetricDescriptor metricDescriptor) {
        LongHistogram recorder = meter.histogramBuilder(sanitizeMetricName(metricDescriptor.name()))
                .ofLongs()
                .setDescription(metricDescriptor.description())
                .setUnit(sanitizeUnit(metricDescriptor.unit()))
                .build();
        return recorder::record;
    }

    private DoubleValueRecorder createDoubleValueRecorder(MetricDescriptor metricDescriptor) {
        DoubleHistogram recorder = meter.histogramBuilder(sanitizeMetricName(metricDescriptor.name()))
                .setDescription(metricDescriptor.description())
                .setUnit(sanitizeUnit(metricDescriptor.unit()))
                .build();
        return recorder::record;
    }

    @Override
    public AutoCloseable registerLongGauge(MetricDescriptor descriptor, Attributes attributes, LongSupplier supplier) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        Objects.requireNonNull(supplier, "supplier must not be null");
        return meter.gaugeBuilder(sanitizeMetricName(descriptor.name()))
                .ofLongs()
                .setDescription(descriptor.description())
                .setUnit(sanitizeUnit(descriptor.unit()))
                .buildWithCallback(measurement -> measurement.record(supplier.getAsLong(), attributes));
    }

    @Override
    public void close() {
        flushBeforeClose();
        meterProvider.close();
    }

    private static Resource createResource(Map<String, String> tags) {
        String serviceName = tags.getOrDefault("service", DEFAULT_SERVICE_NAME);
        return Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), serviceName
        )));
    }

    static String sanitizeMetricName(String name) {
        String sanitized = Objects.requireNonNullElse(name, "").replaceAll("[^A-Za-z0-9_.-]", ".");
        sanitized = sanitized.replaceAll("\\.{2,}", ".");
        if (sanitized.isBlank()) {
            return "jfr.metric";
        }
        if (!Character.isLetter(sanitized.charAt(0))) {
            sanitized = "jfr." + sanitized;
        }
        if (sanitized.length() <= 63) {
            return sanitized;
        }

        String hashSuffix = Integer.toHexString(sanitized.hashCode());
        int prefixLength = 63 - hashSuffix.length() - 1;
        return sanitized.substring(0, prefixLength) + "." + hashSuffix;
    }

    private static String sanitizeUnit(String unit) {
        return unit == null ? "" : unit;
    }

    private static MetricExporter createMetricExporter(String endpoint) {
        return OtlpGrpcMetricExporter.builder()
                .setEndpoint(endpoint)
                .build();
    }

    private static String validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        return endpoint;
    }

    private static String instrumentKey(MetricDescriptor metricDescriptor) {
        return sanitizeMetricName(metricDescriptor.name());
    }

    private void flushBeforeClose() {
        CompletableResultCode flushResult = meterProvider.forceFlush();
        if (!flushResult.join(10, TimeUnit.SECONDS).isSuccess()) {
            log.error("Failed to flush OpenTelemetry metrics before shutdown");
        }
    }
}
