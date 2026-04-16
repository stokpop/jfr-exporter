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
    private boolean debug = false;
    private Duration duration = null;
    private String influxRetentionPolicy = "autogen";
    private long bigObjectThresholdBytes = 256_000L;
    private long bigObjectSampleWeightThresholdBytes = 48_000_000L;
    private boolean enableStackTraces = true;

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

            if (matches(arg,  "--otlpEndpoint", "otlpEndpoint")) {
                arguments.otlpEndpoint = options.remove();
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

    public String getOtlpEndpoint() {
        return otlpEndpoint;
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
                ", influxUrl='" + influxUrl + '\'' +
                ", influxDatabase='" + influxDatabase + '\'' +
                ", influxUser='" + influxUser + '\'' +
                ", influxPassword='" + influxPassword + '\'' +
                ", debug=" + debug +
                ", duration=" + duration +
                ", enableStackTraces=" + enableStackTraces +
                '}';
    }

    public String getInfluxRetentionPolicy() {
        return influxRetentionPolicy;
    }

    public boolean isEnableStackTraces() {
        return enableStackTraces;
    }
}
