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
package io.perfana.jfr.influx;

import io.perfana.jfr.Logger;
import io.perfana.jfr.ProcessedJfrEvent;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class InfluxWriterNative implements InfluxWriter {

    private static final Logger log = Logger.getLogger(InfluxWriterNative.class);

    private final HttpClient httpClient;

    private final List<String> metricsBuffer = new ArrayList<>();

    private final AtomicLong nextFlush = new AtomicLong(System.currentTimeMillis());

    private static final int maxBatchSize = 1_000;
    private static final int maxBatchAgeMs = 5_000;

    private final Object bufferLock = new Object();

    private final URI writeUri;

    private final boolean enableStacktraces;

    private final Map<String, String> globalTags;

    private final String generatedTags;

    public InfluxWriterNative(InfluxWriterConfig config) {

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        Map<String, String> requestParams = initializeRequestParams(config);
        this.writeUri = createWriteUri(requestParams, config.url());

        this.enableStacktraces = config.enableStacktraces();

        // prefixed with comma if tags is not empty
        this.globalTags = config.tags();
        this.generatedTags = prepareTagsForInflux(globalTags);
    }
    @Override
    public boolean isHealthy() {
        // TODO: implement health check
        return true;
    }

    @Override
    public void writeMetricPoint(ProcessedJfrEvent event) {

        // Line protocol: https://github.com/influxdata/influxdb/blob/master/tsdb/README.md
        // jdk.SafepointEnd,service=afterburner duration=0.172 1691147875098417583

        Instant timestamp = event.timestamp() == null ? Instant.now() : event.timestamp();
        long timestampEpochNano = InfluxWriter.toEpochNs(timestamp);

        String tags = event.tags().isEmpty() ? generatedTags : prepareTagsForInflux(globalTags, event.tags());
        String key = event.measurementName() + tags;

        // note: field String values must be quoted, unlike tags
        Map<String, String> fields = new HashMap<>();
        fields.put(event.field(), String.valueOf(event.value()));

        if (!event.stacktrace().isEmpty()) {
            String stacktrace = InfluxWriter.formatStacktrace(event.stacktrace(), enableStacktraces);
            fields.put("stacktrace", escapeFieldForInflux(stacktrace));
        }

        if (!event.extraFields().isEmpty()) {
            for (Map.Entry<String, Object> entry : event.extraFields().entrySet()) {
                Object value = entry.getValue();
                String escapedValue = escapeFieldForInflux(value);
                fields.put(entry.getKey(), escapedValue);
            }
        }

        String generatedFields = fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));

        boolean useBuffer = true;

        String dataToSend = key + " " + generatedFields + " " + timestampEpochNano;

        if (useBuffer) {
            bufferAndSendToInflux(dataToSend);
        } else {
            sendInfluxData(dataToSend);
        }
    }

    static @NotNull String prepareTagsForInflux(Map<String,String> globalTags, Map<String,String> eventTags) {
        if (eventTags.isEmpty()) {
            return prepareTagsForInflux(globalTags);
        }
        Map<String, String> allTags = new HashMap<>(globalTags);
        allTags.putAll(eventTags);
        return prepareTagsForInflux(allTags);
    }

    private static @NotNull String prepareTagsForInflux(Map<String,String> origTags) {
        if (origTags.isEmpty()) {
            return "";
        }
        else if (origTags.size() == 1) {
            Map.Entry<String,String> entry = origTags.entrySet().iterator().next();
            return "," + entry.getKey() + "=" + escapeTagForInflux(entry.getValue());
        }
        else {
            // tags are sorted alphabetically for better performance in InfluxDB
            // (is this worth creating a 'heavy' TreeMap() here?)
            SortedMap<String, String> sortedTags = new TreeMap<>();

            for (Map.Entry<String, String> entry : origTags.entrySet()) {
                String escapedValue = escapeTagForInflux(entry.getValue());
                sortedTags.put(entry.getKey(), escapedValue);
            }

            return "," + sortedTags.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));
        }
    }

    private void bufferAndSendToInflux(String data) {
        log.trace("Buffering data: %s", data);
        Optional<String> metricsToWrite = addDataToBufferAndReturnAllWhenBufferIsFullThreadSafe(data);
        metricsToWrite.ifPresent(this::sendInfluxData);
    }

    private Optional<String> addDataToBufferAndReturnAllWhenBufferIsFullThreadSafe(String data) {
        synchronized (bufferLock) {
            metricsBuffer.add(data);
            if (bufferIsFullOrExpired()) {
                return flushBuffer();
            }
        }
        return Optional.empty();
    }

    @NotNull
    private Optional<String> flushBuffer() {
        log.debug("Flushing %d metrics to InfluxDB", metricsBuffer.size());
        String allData = String.join("\n", metricsBuffer);
        clearBuffer();
        return Optional.of(allData);
    }

    private void clearBuffer() {
        nextFlush.set(System.currentTimeMillis() + maxBatchAgeMs);
        metricsBuffer.clear();
    }

    private boolean bufferIsFullOrExpired() {
        return (metricsBuffer.size() > maxBatchSize) || (nextFlush.get() < System.currentTimeMillis());
    }

    private void sendInfluxData(String data) {
        log.trace("Writing data to InfluxDB: %s", data);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(writeUri)
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "service/x-www-form-urlencoded")
                .header("User-agent", "jfr-exporter/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(data))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            log.trace("InfluxDB response: %d %s", statusCode, response.body());
            if (statusCode != 204) {
                log.error("Failed to send request to InfluxDB: (%d) %s", statusCode, response.body());
            }
        } catch (IOException e) {
            log.error("Failed to send request to InfluxDB: (%s) %s", e.getClass().getSimpleName(), e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to send request to InfluxDB: (%s) %s", e.getClass().getSimpleName(), e.getMessage());
        }

    }

    @NotNull
    private URI createWriteUri(Map<String, String> requestParams1, String baseUrl) {
        String requestParamsString = requestParams1.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));

        return URI.create(baseUrl + "/write?" + requestParamsString);
    }

    private static Map<String, String> initializeRequestParams(InfluxWriterConfig config) {
        var params = new HashMap<String, String>();
        params.put("db", config.database());
        params.put("u", config.username());
        params.put("p", config.password());
        params.put("precision", "n");
        return Collections.unmodifiableMap(params);
    }

    @NotNull
    private static String escapeFieldForInflux(Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        String stringValue = value.toString();
        if (stringValue.contains("\"") || stringValue.contains("\\")) {
            return "\"" + escapeSlashesAndDoubleQuotes(value.toString()) + "\"";
        }
        return "\"" + value + "\"";
    }
    @NotNull
    private static String escapeTagForInflux(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.isBlank()) {
            return "<blank>";
        }
        return value.replace(" ", "\\ ").replace(",", "\\,");
    }

    private static String escapeSlashesAndDoubleQuotes(String text) {
        return text.replace("\"", "\\\"").replace("\\", "\\\\");
    }

    @Override
    public void close() throws Exception {
        flushBuffer().ifPresent(this::sendInfluxData);
    }
}
