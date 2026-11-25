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
package io.perfana.jfr;

import io.perfana.jfr.otel.OpenTelemetryMeterProvider;

import java.time.Duration;
import java.util.*;

public class Arguments {
    private Integer processId = null;
    private Map<String,String> tags = new HashMap<>();
    private String influxUrl = null;
    private String influxDatabase = "jfr";
    private String influxUser = "";
    private String influxPassword = "";
    private String otlpEndpoint = null;
    private OpenTelemetryMeterProvider.OtlpProtocol otlpProtocol = null;
    private OpenTelemetryMeterProvider.OtlpSignal otlpSignal = OpenTelemetryMeterProvider.OtlpSignal.METRICS;
    private OpenTelemetryMeterProvider.OtlpFormat otlpFormat = OpenTelemetryMeterProvider.OtlpFormat.PROTOBUF;
    private boolean enableExecutorMBeans = false;
    private boolean debug = false;
    private Duration duration = null;
    private String influxRetentionPolicy = "autogen";
    private long bigObjectThresholdBytes = 256_000L;
    private long bigObjectSampleWeightThresholdBytes = 48_000_000L;
    private boolean enableStackTraces = true;
    private int stacktraceLogSample = 0;

    public static String usage() {
        return "Usage: java JfrExporter " +
                "--debug,-d --processId,-p <processId> " +
                " --duration <ISO-duration>" +
                " --application,-a <application - deprecated, use tags instead>" +
                " --tags, -t <comma separated list of tag-name=tag-value pairs>" +
                " --bigObjectThreshold <bytes>" +
                " --bigObjectSampleWeightThreshold <bytes>" +
                " --disableStackTraces" +
                " --otlpEndpoint <otlpEndpoint>" +
                " --otlpProtocol <http|grpc>" +
                " --otlpSignal <metrics>" +
                " --otlpFormat <protobuf|json>" +
                " --enableExecutorMBeans" +
                " --stacktraceLogSample <N: send stacktrace as OTLP log for 1 in N events, 0=off>" +
                " --influxUrl <influxUrl> --influxDatabase <influxDatabase>" +
                " --influxUser <influxUser> --influxPassword <influxPassword>";
    }

    public static void print(String message) {
        System.out.println(message);
    }

    public static Arguments parseArgs(String[] args) {

        Queue<String> options = new ArrayDeque<>(Arrays.asList(args));

        Arguments arguments = new Arguments();

        while (!options.isEmpty()) {
            String arg = options.remove();

            if (matches(arg, "-h", "--help", "help")) {
                print(Arguments.usage());
                System.exit(1);
            }

            if (matches(arg, "-d", "--debug", "debug")) {
                arguments.debug = true;
                continue;
            }

            if (matches(arg, "--disableStackTraces", "disableStackTraces")) {
                arguments.enableStackTraces = false;
                continue;
            }

            if (matches(arg, "-p", "--processId", "processId")) {
                String processId = options.remove();
                arguments.processId = Integer.parseInt(processId);
                continue;
            }

            if (matches(arg, "-a", "--application", "application")) {
                print("WARN: application option is deprecated, use tags instead");
                arguments.tags.put("application", options.remove());
                continue;
            }

            if (matches(arg, "-t", "--tag", "tag")) {
                // expect tag=name/value
                addTagToMap(options.remove(), arguments.tags);
                continue;
            }

            if (matches(arg,  "--influxUrl", "influxUrl")) {
                arguments.influxUrl = options.remove();
                continue;
            }

            if (matches(arg, "", "--stacktraceLogSample", "stacktraceLogSample")) {
                arguments.stacktraceLogSample = Integer.parseInt(options.remove());
                continue;
            }

            if (matches(arg,  "--otlpEndpoint", "otlpEndpoint")) {
                arguments.otlpEndpoint = options.remove();
                continue;
            }

            if (matches(arg,  "--otlpProtocol", "otlpProtocol")) {
                arguments.otlpProtocol = OpenTelemetryMeterProvider.OtlpProtocol.parse(options.remove());
                continue;
            }

            if (matches(arg,  "--otlpSignal", "otlpSignal")) {
                arguments.otlpSignal = OpenTelemetryMeterProvider.OtlpSignal.parse(options.remove());
                continue;
            }

            if (matches(arg,  "--otlpFormat", "otlpFormat")) {
                arguments.otlpFormat = OpenTelemetryMeterProvider.OtlpFormat.parse(options.remove());
                continue;
            }

            if (matches(arg, "--enableExecutorMBeans", "enableExecutorMBeans")) {
                arguments.enableExecutorMBeans = true;
                continue;
            }

            if (matches(arg, "", "--influxDatabase", "influxDatabase")) {
                arguments.influxDatabase = options.remove();
                continue;
            }

            if (matches(arg, "", "--influxUser", "influxUser")) {
                arguments.influxUser = options.remove();
                continue;
            }

            if (matches(arg, "", "--influxPassword", "influxPassword")) {
                arguments.influxPassword = options.remove();
                continue;
            }

            if (matches(arg, "", "--duration", "duration")) {
                arguments.duration = Duration.parse(options.remove());
                continue;
            }

            if (matches(arg, "", "--bigObjectThreshold", "bigObjectThreshold")) {
                arguments.bigObjectThresholdBytes = Long.parseLong(options.remove());
                continue;
            }

            if (matches(arg, "", "--bigObjectSampleWeightThreshold", "bigObjectSampleWeightThreshold")) {
                arguments.bigObjectSampleWeightThresholdBytes = Long.parseLong(options.remove());
                continue;
            }

            print("WARN: unknown option: " + arg);

        }

        return arguments;
    }

    private static void addTagToMap(String tagWithSlash, Map<String, String> map) {
        String[] keyValue = tagWithSlash.split("/");
        if (keyValue.length == 2) {
            map.put(keyValue[0], keyValue[1]);
        }
        else {
            print("ERROR invalid tags format, ignoring: " + tagWithSlash);
        }
    }

    private static boolean matches(String arg, String... matchers) {
        return Arrays.asList(matchers).contains(arg);
    }

    public Integer getProcessId() {
        return processId;
    }

    public Map<String, String> getTags() {
        return Collections.unmodifiableMap(tags);
    }

    public long getBigObjectThresholdBytes() {
        return bigObjectThresholdBytes;
    }
    public long getBigObjectSampleWeigthThresholdBytes() {
        return bigObjectSampleWeightThresholdBytes;
    }

    public String getInfluxUrl() {
        return influxUrl;
    }

    public String getInfluxDatabase() {
        return influxDatabase;
    }

    public int getStacktraceLogSample() {
        return stacktraceLogSample;
    }

    public String getOtlpEndpoint() {
        return otlpEndpoint;
    }

    public OpenTelemetryMeterProvider.OtlpProtocol getOtlpProtocol() {
        return otlpProtocol;
    }

    public OpenTelemetryMeterProvider.OtlpSignal getOtlpSignal() {
        return otlpSignal;
    }

    public OpenTelemetryMeterProvider.OtlpFormat getOtlpFormat() {
        return otlpFormat;
    }

    public String getInfluxUser() {
        return influxUser;
    }

    public String getInfluxPassword() {
        return influxPassword;
    }

    public boolean isDebug() {
        return debug;
    }

    public boolean isEnableExecutorMBeans() {
        return enableExecutorMBeans;
    }

    public boolean hasMetricExporter() {
        return otlpEndpoint != null || influxUrl != null;
    }

    public boolean isMBeansOnlyMode() {
        return enableExecutorMBeans && !hasMetricExporter();
    }

    public Duration getDuration() {
        return duration;
    }

    @Override
    public String toString() {
        return "Arguments{" +
                "processId=" + processId +
                ", tags ='" + tags + '\'' +
                ", bigObjectThreshold='" + bigObjectThresholdBytes + '\'' +
                ", otlpEndpoint='" + otlpEndpoint + '\'' +
                ", otlpProtocol='" + otlpProtocol + '\'' +
                ", otlpSignal='" + otlpSignal + '\'' +
                ", otlpFormat='" + otlpFormat + '\'' +
                ", enableExecutorMBeans=" + enableExecutorMBeans +
                ", influxUrl='" + influxUrl + '\'' +
                ", influxDatabase='" + influxDatabase + '\'' +
                ", influxUser='" + influxUser + '\'' +
                ", influxPassword='" + influxPassword + '\'' +
                ", debug=" + debug +
                ", duration=" + duration +
                ", enableStackTraces=" + enableStackTraces +
                ", stacktraceLogSample=" + stacktraceLogSample +
                '}';
    }

    public String getInfluxRetentionPolicy() {
        return influxRetentionPolicy;
    }

    public boolean isEnableStackTraces() {
        return enableStackTraces;
    }
}
