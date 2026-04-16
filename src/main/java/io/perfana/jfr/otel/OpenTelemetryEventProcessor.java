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
import io.opentelemetry.api.common.AttributesBuilder;
import io.perfana.jfr.JfrEventProcessor;
import io.perfana.jfr.Logger;
import io.perfana.jfr.ProcessedJfrEvent;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OpenTelemetryEventProcessor implements JfrEventProcessor, AutoCloseable {

    private static final Logger log = Logger.getLogger(OpenTelemetryEventProcessor.class);
    private static final Set<String> IDENTIFIER_FIELD_SUFFIXES = Set.of("id", "ids", "identifier", "identifiers");
    private static final Set<String> ALLOCATION_MEASUREMENTS = Set.of("big-allocations", "object-allocation-sample");
    private static final JfrMeterProvider.MetricDescriptor ALLOCATION_SIZE_METRIC =
            new JfrMeterProvider.MetricDescriptor(
                    "jfr.object.allocation.bytes",
                    "Recorded JFR object allocation sizes.",
                    "By");

    private final JfrMeterProvider meterProvider;
    private final Map<String, String> defaultTags;
    private final Map<MetricId, JfrMeterProvider.LongValueRecorder> longRecorders = new ConcurrentHashMap<>();
    private final Map<MetricId, JfrMeterProvider.DoubleValueRecorder> doubleRecorders = new ConcurrentHashMap<>();
    private final JfrMeterProvider.LongValueRecorder allocationSizeRecorder;

    public OpenTelemetryEventProcessor(JfrMeterProvider meterProvider, Map<String, String> defaultTags) {
        if (meterProvider == null) {
            throw new IllegalArgumentException("meterProvider must not be null");
        }
        this.meterProvider = meterProvider;
        this.defaultTags = defaultTags == null ? Map.of() : Map.copyOf(defaultTags);
        this.allocationSizeRecorder = meterProvider.registerLongValueRecorder(ALLOCATION_SIZE_METRIC);
    }

    @Override
    public void processEvent(ProcessedJfrEvent event) {
        log.debug("Process OpenTelemetry event: %s", event.toStringShort());

        Attributes attributes = buildAttributes(event);
        MetricId primaryMetric = new MetricId(event.measurementName(), event.field());
        recordMetric(primaryMetric, event.value(), attributes);

        event.extraFields().forEach((field, value) -> {
            MetricId metricId = new MetricId(event.measurementName(), field);
            if (shouldRecordAsMetric(metricId, value)) {
                Number number = (Number) value;
                recordMetric(metricId, number, attributes);
            }
        });

        if (isAllocationSizeMetric(primaryMetric) && isIntegralNumber(event.value())) {
            allocationSizeRecorder.record(event.value().longValue(), attributes);
        }
    }

    @Override
    public void close() throws Exception {
        meterProvider.close();
    }

    private void recordMetric(MetricId metricId, Number number, Attributes attributes) {
        if (number instanceof Float || number instanceof Double) {
            doubleRecorders.computeIfAbsent(metricId, this::registerDoubleRecorder).record(number.doubleValue(), attributes);
        } else {
            longRecorders.computeIfAbsent(metricId, this::registerLongRecorder).record(number.longValue(), attributes);
        }
    }

    private JfrMeterProvider.LongValueRecorder registerLongRecorder(MetricId metricId) {
        return meterProvider.registerLongValueRecorder(metricId.descriptor());
    }

    private JfrMeterProvider.DoubleValueRecorder registerDoubleRecorder(MetricId metricId) {
        return meterProvider.registerDoubleValueRecorder(metricId.descriptor());
    }

    private Attributes buildAttributes(ProcessedJfrEvent event) {
        AttributesBuilder builder = Attributes.builder();
        defaultTags.forEach(builder::put);
        event.tags().forEach(builder::put);
        event.extraFields().forEach((key, value) -> {
            if (!shouldRecordAsMetric(new MetricId(event.measurementName(), key), value)) {
                putAttribute(builder, key, value);
            }
        });
        return builder.build();
    }

    private static boolean shouldRecordAsMetric(MetricId metricId, Object value) {
        return value instanceof Number && !isIdentifierField(metricId.field());
    }

    private static boolean isAllocationSizeMetric(MetricId metricId) {
        return ALLOCATION_MEASUREMENTS.contains(metricId.measurementName()) && "bytes".equals(metricId.field());
    }

    private static boolean isIntegralNumber(Number number) {
        return !(number instanceof Float || number instanceof Double);
    }

    private static boolean isIdentifierField(String field) {
        String normalized = normalizeFieldName(field);
        if (normalized.isBlank()) {
            return false;
        }

        String[] parts = normalized.split("-");
        return IDENTIFIER_FIELD_SUFFIXES.contains(parts[parts.length - 1]);
    }

    private static String normalizeFieldName(String field) {
        if (field == null) {
            return "";
        }

        return field.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .toLowerCase(Locale.ROOT)
                .replaceAll("^-+|-+$", "");
    }

    private static void putAttribute(AttributesBuilder builder, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean boolValue) {
            builder.put(key, boolValue);
        } else if (value instanceof Float || value instanceof Double) {
            builder.put(key, ((Number) value).doubleValue());
        } else if (value instanceof Number numberValue) {
            builder.put(key, numberValue.longValue());
        } else {
            builder.put(key, value.toString());
        }
    }

    private record MetricId(String measurementName, String field) {
        private String metricName() {
            return "jfr." + measurementName + "." + field;
        }

        private JfrMeterProvider.MetricDescriptor descriptor() {
            return new JfrMeterProvider.MetricDescriptor(metricName(), description(), unit());
        }

        private String description() {
            return "JFR metric for " + measurementName + "." + field;
        }

        private String unit() {
            String lowerField = field.toLowerCase();
            if (lowerField.contains("bytes") || lowerField.contains("size") || lowerField.contains("reserved")
                    || lowerField.contains("committed") || lowerField.contains("heap") || lowerField.contains("memory")
                    || lowerField.contains("peak")) {
                return "By";
            }
            if (lowerField.contains("duration-ns") || lowerField.endsWith("ns")) {
                return "ns";
            }
            if (lowerField.contains("duration-ms") || lowerField.endsWith("ms")) {
                return "ms";
            }
            if (lowerField.contains("count") || lowerField.contains("slices")) {
                return "1";
            }
            return "";
        }
    }
}
