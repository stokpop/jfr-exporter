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

import io.perfana.jfr.event.*;
import io.perfana.jfr.influx.InfluxEventProcessor;
import io.perfana.jfr.influx.InfluxWriter;
import io.perfana.jfr.influx.InfluxWriterConfig;
import io.perfana.jfr.influx.InfluxWriterNative;
import org.jetbrains.annotations.NotNull;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class JfrExporter {

    private static final Logger log = Logger.getLogger(JfrExporter.class);

    public static void main(String[] args) {

        Arguments arguments = Arguments.parseArgs(args);

        if (arguments.isDebug()) {
            Logger.enabledDebug();
        }

        JfrExporter jfrExporter = new JfrExporter();
        jfrExporter.start(arguments);

    }

    public void start(Arguments args) {

        if (args.isDebug()) {
            Logger.enabledDebug();
        }

        log.info("Start JfrExporter with arguments: %s", args);

        JfrEventHandler eventHandler = new JfrEventHandler();

        JfrEventProcessor eventProcessor = args.getInfluxUrl() == null
                ? new NoopEventProcessor()
                : createInfluxEventProcessor(args);

            try {
                CpuLoadEvent cpuLoadEvent = new CpuLoadEvent(eventProcessor);
                cpuLoadEvent.getEventSettings().forEach(eventHandler::register);

                SafepointEvent safepointEvent = new SafepointEvent(eventProcessor);
                safepointEvent.getEventSettings().forEach(eventHandler::register);

                ObjectAllocationSampleEvent objectAllocationSampleEvent =
                        new ObjectAllocationSampleEvent(eventProcessor, args.getBigObjectSampleWeigthThresholdBytes());
                objectAllocationSampleEvent.getEventSettings().forEach(eventHandler::register);

                ObjectAllocationEvent objectAllocationEvent =
                        new ObjectAllocationEvent(eventProcessor, args.getBigObjectThresholdBytes());
                objectAllocationEvent.getEventSettings().forEach(eventHandler::register);

                GCHeapEvent gcHeapEvent = new GCHeapEvent(eventProcessor);
                gcHeapEvent.getEventSettings().forEach(eventHandler::register);

                JavaStatisticsEvent javaStatisticsEvent = new JavaStatisticsEvent(eventProcessor);
                javaStatisticsEvent.getEventSettings().forEach(eventHandler::register);

                MonitorEvent monitorEvent = new MonitorEvent(eventProcessor);
                monitorEvent.getEventSettings().forEach(eventHandler::register);

                SocketEvent socketEvent = new SocketEvent(eventProcessor);
                socketEvent.getEventSettings().forEach(eventHandler::register);

                NativeMemoryEvent nativeMemoryEvent = new NativeMemoryEvent(eventProcessor);
                nativeMemoryEvent.getEventSettings().forEach(eventHandler::register);

                ContainerEvent containerEvent = new ContainerEvent(eventProcessor);
                containerEvent.getEventSettings().forEach(eventHandler::register);

                VirtualThreadEvent virtualThreadEvent = new VirtualThreadEvent(eventProcessor);
                virtualThreadEvent.getEventSettings().forEach(eventHandler::register);

                JfrConnector jfrConnector = new JfrConnector(eventHandler);

                if (args.getProcessId() == null) {
                    jfrConnector.connectInternalJVM(args.getDuration());
                } else {
                    jfrConnector.connectRemoteJvm(args.getProcessId(), args.getDuration());
                }
            } finally {
                autoClose(eventProcessor);
            }
        }

    private static void autoClose(JfrEventProcessor eventProcessor) {
        try {
            if (eventProcessor instanceof AutoCloseable autoCloseable) {
                autoCloseable.close();
            }
        } catch (Exception e) {
            log.error("Error closing event processor: %s", e.getMessage());
        }
    }

    private JfrEventProcessor createInfluxEventProcessor(Arguments args) {
        InfluxWriterConfig config = new InfluxWriterConfig(
                args.getInfluxUrl(),
                args.getInfluxDatabase(),
                args.getInfluxUser(),
                args.getInfluxPassword(),
                args.getInfluxRetentionPolicy(),
                args.getTags(),
                args.isEnableStackTraces());
        InfluxWriter writer = new InfluxWriterNative(config);
        return new InfluxEventProcessor(writer);
    }

    public static void premain(String args, Instrumentation instrumentation){
        log.info("premain: %s", (args == null ? "<no args>" : args));
        JfrExporter jfrExporter = new JfrExporter();
        String[] argsArray = splitAgentArgs(args);

        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("jfr-exporter");
            thread.setDaemon(true);
            return thread;
        };

        CompletableFuture.runAsync(
                () -> jfrExporter.start(Arguments.parseArgs(argsArray)), Executors.newSingleThreadExecutor(threadFactory)
        );
    }

    @NotNull
    static String[] splitAgentArgs(String args) {
        return args == null ? new String[]{} : args.split("[=,]");
    }

    public static void agentmain(String args, Instrumentation instrumentation) {
        log.info("agentmain: calling premain");
        premain(args, instrumentation);
    }
}
