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
import io.opentelemetry.sdk.metrics.data.DoublePointData;
import io.opentelemetry.sdk.metrics.data.GaugeData;
import io.opentelemetry.sdk.metrics.data.HistogramData;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.LongPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.perfana.jfr.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class OtlpHttpJsonMetricExporter implements MetricExporter {

    private static final Logger log = Logger.getLogger(OtlpHttpJsonMetricExporter.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final URI endpoint;
    private final HttpClient httpClient;

    OtlpHttpJsonMetricExporter(String endpoint) {
        this.endpoint = URI.create(endpoint);
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.CUMULATIVE;
    }

    @Override
    public CompletableResultCode export(Collection<MetricData> metricData) {
        try {
            String payload = buildRequestBody(metricData);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(HTTP_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 == 2) {
                return CompletableResultCode.ofSuccess();
            }
            log.error("Failed to export OTLP HTTP JSON metrics, status code: %s", response.statusCode());
        } catch (Exception e) {
            log.error("Failed to export OTLP HTTP JSON metrics: %s", e.getMessage());
        }
        return CompletableResultCode.ofFailure();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    private static String buildRequestBody(Collection<MetricData> metricData) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"resourceMetrics\":[");
        Iterator<MetricData> iterator = metricData.iterator();
        while (iterator.hasNext()) {
            appendResourceMetric(json, iterator.next());
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append("]}");
        return json.toString();
    }

    private static void appendResourceMetric(StringBuilder json, MetricData metric) throws IOException {
        json.append('{');
        json.append("\"resource\":{");
        json.append("\"attributes\":[");
        appendAttributes(json, metric.getResource().getAttributes());
        json.append("]},");
        json.append("\"scopeMetrics\":[{");
        appendScope(json, metric);
        json.append(",\"metrics\":[");
        appendMetric(json, metric);
        json.append("]}]} ");
    }

    private static void appendScope(StringBuilder json, MetricData metric) {
        json.append("\"scope\":{");
        appendJsonField(json, "name", metric.getInstrumentationScopeInfo().getName());
        String version = metric.getInstrumentationScopeInfo().getVersion();
        if (version != null && !version.isBlank()) {
            json.append(',');
            appendJsonField(json, "version", version);
        }
        json.append('}');
    }

    private static void appendMetric(StringBuilder json, MetricData metric) throws IOException {
        json.append('{');
        appendJsonField(json, "name", metric.getName());
        if (!metric.getDescription().isBlank()) {
            json.append(',');
            appendJsonField(json, "description", metric.getDescription());
        }
        if (!metric.getUnit().isBlank()) {
            json.append(',');
            appendJsonField(json, "unit", metric.getUnit());
        }

        switch (metric.getType()) {
            case HISTOGRAM -> appendHistogram(json, metric.getHistogramData());
            case LONG_GAUGE -> appendLongGauge(json, metric.getLongGaugeData());
            case DOUBLE_GAUGE -> appendDoubleGauge(json, metric.getDoubleGaugeData());
            default -> throw new IOException("Unsupported metric type for OTLP HTTP JSON export: " + metric.getType());
        }
        json.append('}');
    }

    private static void appendHistogram(StringBuilder json, HistogramData histogramData) {
        json.append(",\"histogram\":{");
        appendJsonField(json, "aggregationTemporality", "AGGREGATION_TEMPORALITY_CUMULATIVE");
        json.append(",\"dataPoints\":[");
        Iterator<HistogramPointData> iterator = histogramData.getPoints().iterator();
        while (iterator.hasNext()) {
            appendHistogramPoint(json, iterator.next());
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append("]}");
    }

    private static void appendHistogramPoint(StringBuilder json, HistogramPointData point) {
        json.append('{');
        appendStringNumberField(json, "startTimeUnixNano", point.getStartEpochNanos());
        json.append(',');
        appendStringNumberField(json, "timeUnixNano", point.getEpochNanos());
        json.append(',');
        appendAttributesField(json, point.getAttributes());
        json.append(',');
        appendStringNumberField(json, "count", point.getCount());
        json.append(',');
        appendNumberField(json, "sum", point.getSum());
        if (point.hasMin()) {
            json.append(',');
            appendNumberField(json, "min", point.getMin());
        }
        if (point.hasMax()) {
            json.append(',');
            appendNumberField(json, "max", point.getMax());
        }
        json.append(",\"bucketCounts\":[");
        appendLongArray(json, point.getCounts());
        json.append("],\"explicitBounds\":[");
        appendDoubleArray(json, point.getBoundaries());
        json.append("]}");
    }

    private static void appendLongGauge(StringBuilder json, GaugeData<LongPointData> gaugeData) {
        json.append(",\"gauge\":{\"dataPoints\":[");
        Iterator<LongPointData> iterator = gaugeData.getPoints().iterator();
        while (iterator.hasNext()) {
            appendLongGaugePoint(json, iterator.next());
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append("]}");
    }

    private static void appendLongGaugePoint(StringBuilder json, LongPointData point) {
        json.append('{');
        appendStringNumberField(json, "startTimeUnixNano", point.getStartEpochNanos());
        json.append(',');
        appendStringNumberField(json, "timeUnixNano", point.getEpochNanos());
        json.append(',');
        appendAttributesField(json, point.getAttributes());
        json.append(',');
        appendStringNumberField(json, "asInt", point.getValue());
        json.append('}');
    }

    private static void appendDoubleGauge(StringBuilder json, GaugeData<DoublePointData> gaugeData) {
        json.append(",\"gauge\":{\"dataPoints\":[");
        Iterator<DoublePointData> iterator = gaugeData.getPoints().iterator();
        while (iterator.hasNext()) {
            appendDoubleGaugePoint(json, iterator.next());
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
        json.append("]}");
    }

    private static void appendDoubleGaugePoint(StringBuilder json, DoublePointData point) {
        json.append('{');
        appendStringNumberField(json, "startTimeUnixNano", point.getStartEpochNanos());
        json.append(',');
        appendStringNumberField(json, "timeUnixNano", point.getEpochNanos());
        json.append(',');
        appendAttributesField(json, point.getAttributes());
        json.append(',');
        appendNumberField(json, "asDouble", point.getValue());
        json.append('}');
    }

    private static void appendAttributesField(StringBuilder json, Attributes attributes) {
        json.append("\"attributes\":[");
        appendAttributes(json, attributes);
        json.append(']');
    }

    private static void appendAttributes(StringBuilder json, Attributes attributes) {
        Iterator<? extends Map.Entry<AttributeKey<?>, Object>> iterator = attributes.asMap().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<AttributeKey<?>, Object> entry = iterator.next();
            appendAttribute(json, entry.getKey().getKey(), entry.getValue());
            if (iterator.hasNext()) {
                json.append(',');
            }
        }
    }

    private static void appendAttribute(StringBuilder json, String key, Object value) {
        json.append('{');
        appendJsonField(json, "key", key);
        json.append(",\"value\":{");
        appendAnyValue(json, value);
        json.append("}}");
    }

    private static void appendAnyValue(StringBuilder json, Object value) {
        if (value instanceof Boolean boolValue) {
            json.append("\"boolValue\":").append(boolValue);
        } else if (value instanceof Float || value instanceof Double) {
            appendNumberField(json, "doubleValue", ((Number) value).doubleValue());
        } else if (value instanceof Number number) {
            appendStringNumberField(json, "intValue", number.longValue());
        } else {
            appendJsonField(json, "stringValue", String.valueOf(value));
        }
    }

    private static void appendLongArray(StringBuilder json, List<Long> values) {
        for (int i = 0; i < values.size(); i++) {
            json.append('"').append(values.get(i)).append('"');
            if (i < values.size() - 1) {
                json.append(',');
            }
        }
    }

    private static void appendDoubleArray(StringBuilder json, List<Double> values) {
        for (int i = 0; i < values.size(); i++) {
            json.append(values.get(i));
            if (i < values.size() - 1) {
                json.append(',');
            }
        }
    }

    private static void appendJsonField(StringBuilder json, String name, String value) {
        json.append('"').append(escape(name)).append("\":\"").append(escape(value)).append('"');
    }

    private static void appendStringNumberField(StringBuilder json, String name, long value) {
        json.append('"').append(escape(name)).append("\":\"").append(value).append('"');
    }

    private static void appendNumberField(StringBuilder json, String name, double value) {
        json.append('"').append(escape(name)).append("\":").append(value);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

