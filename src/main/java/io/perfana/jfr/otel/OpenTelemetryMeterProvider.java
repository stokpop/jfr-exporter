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
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.perfana.jfr.Logger;

import java.net.URI;
import java.net.URISyntaxException;
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
        this(endpoint, null, OtlpSignal.METRICS, OtlpFormat.PROTOBUF, tags);
    }

    public OpenTelemetryMeterProvider(String endpoint, OtlpProtocol configuredProtocol, Map<String, String> tags) {
        this(endpoint, configuredProtocol, OtlpSignal.METRICS, OtlpFormat.PROTOBUF, tags);
    }

    public OpenTelemetryMeterProvider(String endpoint,
                                      OtlpProtocol configuredProtocol,
                                      OtlpSignal configuredSignal,
                                      Map<String, String> tags) {
        this(endpoint, configuredProtocol, configuredSignal, OtlpFormat.PROTOBUF, tags);
    }

    public OpenTelemetryMeterProvider(String endpoint,
                                      OtlpProtocol configuredProtocol,
                                      OtlpSignal configuredSignal,
                                      OtlpFormat configuredFormat,
                                      Map<String, String> tags) {
        this(endpoint, createExporterConfig(endpoint, configuredProtocol, configuredSignal, configuredFormat), tags, METRIC_READER_INTERVAL);
    }

    private OpenTelemetryMeterProvider(String configuredEndpoint,
                                       OtlpExporterConfig config,
                                       Map<String, String> tags,
                                       Duration readerInterval) {
        this(createMetricExporter(config), tags, readerInterval);
        log.info("OpenTelemetry OTLP/%s metrics exporter configured for base endpoint %s using %s", config.protocol().displayName(), configuredEndpoint, config.format().displayName());
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

    static MetricExporter createMetricExporter(OtlpExporterConfig config) {
        if (config.protocol() == OtlpProtocol.HTTP) {
            if (config.format() == OtlpFormat.JSON) {
                return new OtlpHttpJsonMetricExporter(config.endpoint());
            }
            return OtlpHttpMetricExporter.builder()
                    .setEndpoint(config.endpoint())
                    .build();
        }
        if (config.format() == OtlpFormat.JSON) {
            throw new IllegalArgumentException("OTLP JSON format is only supported with HTTP protocol");
        }
        return OtlpGrpcMetricExporter.builder()
                .setEndpoint(config.endpoint())
                .build();
    }

    private static String validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }

        if (containsSignalPath(endpoint)) {
            throw new IllegalArgumentException("endpoint must be a base OTLP collector URL without a signal path like /v1/metrics");
        }

        return endpoint;
    }

    static OtlpExporterConfig createExporterConfig(String endpoint,
                                                   OtlpProtocol configuredProtocol,
                                                   OtlpSignal configuredSignal,
                                                   OtlpFormat configuredFormat) {
        String validatedEndpoint = validateEndpoint(endpoint);
        OtlpSignal signal = configuredSignal == null ? OtlpSignal.METRICS : configuredSignal;
        OtlpProtocol protocol = configuredProtocol == null ? inferProtocol(validatedEndpoint) : configuredProtocol;
        OtlpFormat format = configuredFormat == null ? OtlpFormat.PROTOBUF : configuredFormat;
        String normalizedEndpoint = normalizeEndpoint(validatedEndpoint, protocol, signal);
        return new OtlpExporterConfig(normalizedEndpoint, protocol, signal, format);
    }

    static OtlpProtocol inferProtocol(String endpoint) {
        String normalized = validateEndpoint(endpoint).toLowerCase();
        if (normalized.contains(":4318")) {
            return OtlpProtocol.HTTP;
        }
        return OtlpProtocol.GRPC;
    }

    static String normalizeEndpoint(String endpoint, OtlpProtocol protocol, OtlpSignal signal) {
        String validatedEndpoint = validateEndpoint(endpoint);
        if (protocol != OtlpProtocol.HTTP) {
            return validatedEndpoint;
        }
        if (!validatedEndpoint.matches("https?://.*")) {
            return validatedEndpoint;
        }

        try {
            URI uri = new URI(validatedEndpoint);
            String normalizedPath = appendSignalPath(normalizeBasePath(uri.getPath()), signal.path());
            return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), uri.getPort(), normalizedPath, uri.getQuery(), uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid endpoint URI: " + endpoint, e);
        }
    }

    private static String normalizeBasePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }

        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static boolean containsSignalPath(String endpoint) {
        String normalized = endpoint.toLowerCase();
        for (OtlpSignal signal : OtlpSignal.values()) {
            String signalPath = signal.path().toLowerCase();
            if (normalized.endsWith(signalPath) || normalized.contains(signalPath + "?")) {
                return true;
            }
        }
        return false;
    }

    private static String appendSignalPath(String basePath, String signalPath) {
        if (basePath == null || basePath.isBlank() || "/".equals(basePath)) {
            return signalPath;
        }
        return basePath + signalPath;
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

    record OtlpExporterConfig(String endpoint, OtlpProtocol protocol, OtlpSignal signal, OtlpFormat format) {
        OtlpExporterConfig {
            Objects.requireNonNull(endpoint, "endpoint must not be null");
            Objects.requireNonNull(protocol, "protocol must not be null");
            Objects.requireNonNull(signal, "signal must not be null");
            Objects.requireNonNull(format, "format must not be null");
        }
    }

    public enum OtlpProtocol {
        HTTP,
        GRPC;

        public static OtlpProtocol parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("otlpProtocol must not be blank");
            }
            return switch (value.trim().toLowerCase()) {
                case "http", "http/protobuf", "http-protobuf" -> HTTP;
                case "grpc" -> GRPC;
                default -> throw new IllegalArgumentException("Unsupported otlpProtocol: " + value + ". Use 'http' or 'grpc'.");
            };
        }

        String displayName() {
            return name().toLowerCase();
        }
    }

    public enum OtlpSignal {
        METRICS("/v1/metrics");

        private final String path;

        OtlpSignal(String path) {
            this.path = path;
        }

        public static OtlpSignal parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("otlpSignal must not be blank");
            }
            return switch (value.trim().toLowerCase()) {
                case "metric", "metrics" -> METRICS;
                default -> throw new IllegalArgumentException("Unsupported otlpSignal: " + value + ". Only 'metrics' is currently supported.");
            };
        }

        String path() {
            return path;
        }

        String displayName() {
            return name().toLowerCase();
        }
    }

    public enum OtlpFormat {
        PROTOBUF,
        JSON;

        public static OtlpFormat parse(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("otlpFormat must not be blank");
            }
            return switch (value.trim().toLowerCase()) {
                case "protobuf", "proto", "binary" -> PROTOBUF;
                case "json" -> JSON;
                default -> throw new IllegalArgumentException("Unsupported otlpFormat: " + value + ". Use 'protobuf' or 'json'.");
            };
        }

        String displayName() {
            return name().toLowerCase();
        }
    }
}
