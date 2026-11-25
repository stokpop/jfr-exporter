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
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.perfana.jfr.Logger;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class OpenTelemetryLogEmitter implements JfrLogEmitter {

    private static final Logger log = Logger.getLogger(OpenTelemetryLogEmitter.class);
    private static final String SCOPE_NAME = "io.perfana.jfr";
    private static final String DEFAULT_SERVICE_NAME = "jfr-exporter";

    private final SdkLoggerProvider loggerProvider;
    private final io.opentelemetry.api.logs.Logger otelLogger;

    public OpenTelemetryLogEmitter(String endpoint, OpenTelemetryMeterProvider.OtlpProtocol configuredProtocol,
                                   Map<String, String> tags) {
        this(createExporter(endpoint, configuredProtocol), tags);
        log.info("OpenTelemetry log exporter configured for %s", endpoint);
    }

    private static LogRecordExporter createExporter(String endpoint, OpenTelemetryMeterProvider.OtlpProtocol configuredProtocol) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        OpenTelemetryMeterProvider.OtlpProtocol protocol = configuredProtocol == null
                ? OpenTelemetryMeterProvider.inferProtocol(endpoint)
                : configuredProtocol;
        if (protocol == OpenTelemetryMeterProvider.OtlpProtocol.HTTP) {
            String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            return OtlpHttpLogRecordExporter.builder().setEndpoint(base + "/v1/logs").build();
        }
        return OtlpGrpcLogRecordExporter.builder().setEndpoint(endpoint).build();
    }

    OpenTelemetryLogEmitter(LogRecordExporter exporter, Map<String, String> tags) {
        Objects.requireNonNull(exporter, "exporter must not be null");
        Map<String, String> safeTags = tags == null ? Map.of() : tags;
        String serviceName = safeTags.getOrDefault("service", DEFAULT_SERVICE_NAME);
        this.loggerProvider = SdkLoggerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(
                        Attributes.of(AttributeKey.stringKey("service.name"), serviceName))))
                .addLogRecordProcessor(BatchLogRecordProcessor.builder(exporter).build())
                .build();
        this.otelLogger = loggerProvider.get(SCOPE_NAME);
    }

    @Override
    public void emit(Instant timestamp, String body, Attributes attributes) {
        var builder = otelLogger.logRecordBuilder()
                .setSeverity(Severity.INFO)
                .setSeverityText("INFO")
                .setBody(body)
                .setAllAttributes(attributes);
        if (timestamp != null) {
            builder.setTimestamp(timestamp);
        }
        builder.emit();
    }

    @Override
    public void close() {
        if (!loggerProvider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess()) {
            log.error("Failed to flush OpenTelemetry logs before shutdown");
        }
        loggerProvider.close();
    }
}
