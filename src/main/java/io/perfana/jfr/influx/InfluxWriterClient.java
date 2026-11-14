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

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import io.perfana.jfr.Logger;
import io.perfana.jfr.ProcessedJfrEvent;

import java.util.Map;

public class InfluxWriterClient implements InfluxWriter, AutoCloseable {

    private static final Logger log = Logger.getLogger(InfluxWriterClient.class);
    private final boolean enableStacktraces;

    private Map<String,String> tags;

    private InfluxDBClient influxDBClient;

    private WriteApi writeApi;

    public InfluxWriterClient(InfluxWriterConfig config) {
        this.tags = config.tags();

        this.influxDBClient = InfluxDBClientFactory.createV1(
                config.url(),
                config.username(),
                config.password().toCharArray(),
                config.database(),
                config.retentionPolicy()
        );

        this.writeApi = influxDBClient.makeWriteApi();

        this.enableStacktraces = config.enableStacktraces();
    }

    @Override
    public boolean isHealthy() {
        return influxDBClient.ping();
    }

    @Override
    public void writeMetricPoint(ProcessedJfrEvent event) {

        Point point = Point.measurement(event.measurementName())
                .addTags(tags)
                .addTags(event.tags())
                .addField(event.field(), event.value());

        if (!event.stacktrace().isEmpty()) {
            String stacktrace = InfluxWriter.formatStacktrace(event.stacktrace(), enableStacktraces);
            point.addField("stacktrace", stacktrace);
        }

        if (event.timestamp() != null) {
            long epochNs = InfluxWriter.toEpochNs(event.timestamp());
            point.time(epochNs, WritePrecision.NS);
        }

        for (Map.Entry<String, Object> entry : event.extraFields().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number number) {
                point.addField(entry.getKey(), number);
            }
            else {
                point.addField(entry.getKey(), value.toString());
            }
        }

        log.debug("about to write line protocol: %s", point.toLineProtocol());

        writeApi.writePoint(point);

    }

    @Override
    public void close() throws Exception {
        writeApi.close();
        influxDBClient.close();
    }

}
