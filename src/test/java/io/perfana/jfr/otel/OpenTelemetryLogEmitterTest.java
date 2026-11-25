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

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenTelemetryLogEmitterTest {

    @Test
    void exportsLogRecordWithBodyAttributesTimestampAndServiceName() {
        List<LogRecordData> exported = new ArrayList<>();
        LogRecordExporter exporter = new LogRecordExporter() {
            @Override
            public CompletableResultCode export(Collection<LogRecordData> logs) {
                exported.addAll(logs);
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
        };
        OpenTelemetryLogEmitter emitter = new OpenTelemetryLogEmitter(exporter, Map.of("service", "checkout"));

        emitter.emit(
                Instant.parse("2026-04-11T00:00:00Z"),
                "big-allocations bytes=1000000",
                Attributes.of(AttributeKey.stringKey("code.stacktrace"), "a.B.c(B.java:1)"));
        emitter.close();

        assertEquals(1, exported.size());
        LogRecordData record = exported.get(0);
        assertEquals("big-allocations bytes=1000000", record.getBody().asString());
        assertEquals("a.B.c(B.java:1)", record.getAttributes().get(AttributeKey.stringKey("code.stacktrace")));
        assertEquals("checkout", record.getResource().getAttribute(AttributeKey.stringKey("service.name")));
        assertEquals(Instant.parse("2026-04-11T00:00:00Z"), Instant.ofEpochSecond(0, record.getTimestampEpochNanos()));
    }

    @Test
    void usesHttpExporterForHttpProtocolAndGrpcOtherwise() {
        assertInstanceOf(OtlpHttpLogRecordExporter.class, OpenTelemetryLogEmitter.createExporter(
                "http://localhost:4318", OpenTelemetryMeterProvider.OtlpProtocol.HTTP));
        assertInstanceOf(OtlpHttpLogRecordExporter.class, OpenTelemetryLogEmitter.createExporter(
                "http://localhost:4318", null));
        assertInstanceOf(OtlpGrpcLogRecordExporter.class, OpenTelemetryLogEmitter.createExporter(
                "http://localhost:4317", null));
        assertInstanceOf(OtlpGrpcLogRecordExporter.class, OpenTelemetryLogEmitter.createExporter(
                "http://localhost:4318", OpenTelemetryMeterProvider.OtlpProtocol.GRPC));
    }

    @Test
    void rejectsBlankEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> OpenTelemetryLogEmitter.createExporter(" ", null));
    }

    @Test
    void postsProtobufToV1LogsOverHttp() throws Exception {
        List<String> paths = new CopyOnWriteArrayList<>();
        List<String> contentTypes = new CopyOnWriteArrayList<>();
        List<Integer> bodySizes = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            bodySizes.add(exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
            OpenTelemetryLogEmitter emitter = new OpenTelemetryLogEmitter(
                    endpoint, OpenTelemetryMeterProvider.OtlpProtocol.HTTP, Map.of("service", "checkout"));

            emitter.emit(Instant.now(), "big-allocations bytes=1", Attributes.empty());
            emitter.close();

            assertEquals(List.of("/v1/logs"), paths);
            assertEquals(List.of("application/x-protobuf"), contentTypes);
            assertEquals(1, bodySizes.size());
        } finally {
            server.stop(0);
        }
    }
}
