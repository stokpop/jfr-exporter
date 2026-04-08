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
package io.perfana.jfr.event;

import io.perfana.jfr.*;
import jdk.jfr.consumer.RecordedEvent;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static io.perfana.jfr.JfrUtil.translateStacktrace;

public class VirtualThreadEvent implements OnJfrEvent, JfrEventProvider {

    private static final Logger log = Logger.getLogger(VirtualThreadEvent.class);

    public static final String JDK_VIRTUAL_THREAD_START = "jdk.VirtualThreadStart";
    public static final String JDK_VIRTUAL_THREAD_END = "jdk.VirtualThreadEnd";
    public static final String JDK_VIRTUAL_THREAD_PINNED = "jdk.VirtualThreadPinned";
    public static final String JDK_VIRTUAL_THREAD_SUBMIT_FAILED = "jdk.VirtualThreadSubmitFailed";

    private final JfrEventProcessor eventProcessor;

    private final AtomicLong activeVirtualThreadCount = new AtomicLong(0);

    private final long pinnedMinimumDurationNs = Duration.ofMillis(20).toNanos();

    public VirtualThreadEvent(JfrEventProcessor eventProcessor) {
        if (eventProcessor == null) throw new IllegalArgumentException("eventProcessor must not be null");
        this.eventProcessor = eventProcessor;
    }

    @Override
    public void onEvent(RecordedEvent event) {
        String name = event.getEventType().getName();

        switch (name) {
            case JDK_VIRTUAL_THREAD_START -> handleVirtualThreadStart(event);
            case JDK_VIRTUAL_THREAD_END -> handleVirtualThreadEnd(event);
            case JDK_VIRTUAL_THREAD_PINNED -> handleVirtualThreadPinned(event);
            case JDK_VIRTUAL_THREAD_SUBMIT_FAILED -> handleVirtualThreadSubmitFailed(event);
            default -> log.debug("Ignoring unknown virtual thread event: %s", name);
        }
    }

    private void handleVirtualThreadStart(RecordedEvent event) {
        long count = activeVirtualThreadCount.incrementAndGet();
        log.trace("Virtual thread started, active count: %d", count);
        eventProcessor.processEvent(ProcessedJfrEvent.of(event.getStartTime(), "virtual-threads", "activeCount", count));
    }

    private void handleVirtualThreadEnd(RecordedEvent event) {
        long count = activeVirtualThreadCount.decrementAndGet();
        log.trace("Virtual thread ended, active count: %d", count);
        eventProcessor.processEvent(ProcessedJfrEvent.of(event.getStartTime(), "virtual-threads", "activeCount", count));
    }

    private void handleVirtualThreadPinned(RecordedEvent event) {
        long durationNs = event.getLong("duration");

        log.trace("Virtual thread pinned for %d ns", durationNs);

        if (durationNs > pinnedMinimumDurationNs) {
            List<String> stackTrace = (event.getStackTrace() != null) ? translateStacktrace(event) : List.of();

            String firstStack = stackTrace.isEmpty() ? "<none>" : stackTrace.get(0);
            log.debug("Virtual thread pinned for %d ns at '%s'", durationNs, firstStack);

            ProcessedJfrEvent processedEvent = ProcessedJfrEvent.of(
                    event.getStartTime(),
                    "virtual-thread-pinned",
                    "duration-ns",
                    durationNs,
                    Map.of(),
                    stackTrace);

            eventProcessor.processEvent(processedEvent);
        }
    }

    private void handleVirtualThreadSubmitFailed(RecordedEvent event) {
        long javaThreadId = event.getLong("javaThreadId");
        String exceptionMessage = event.getString("exceptionMessage");

        log.debug("Virtual thread submit failed for thread id %d: %s", javaThreadId, exceptionMessage);

        Map<String, Object> extraFields = Map.of(
                "javaThreadId", javaThreadId,
                "exceptionMessage", exceptionMessage == null ? "<none>" : exceptionMessage
        );

        eventProcessor.processEvent(ProcessedJfrEvent.of(
                event.getStartTime(),
                "virtual-thread-submit-failed",
                "count",
                1,
                extraFields));
    }

    @Override
    public List<JfrEventSettings> getEventSettings() {
        int javaVersion = Runtime.version().feature();
        if (javaVersion < 21) {
            log.info("Virtual thread JFR events require JDK 21 or later (current: JDK %d), skipping.", javaVersion);
            return List.of();
        }
        return List.of(
                JfrEventSettings.of(JDK_VIRTUAL_THREAD_START, this),
                JfrEventSettings.of(JDK_VIRTUAL_THREAD_END, this),
                JfrEventSettings.of(JDK_VIRTUAL_THREAD_PINNED, this)
                        .withThreshold(Duration.ofNanos(pinnedMinimumDurationNs)),
                JfrEventSettings.of(JDK_VIRTUAL_THREAD_SUBMIT_FAILED, this)
        );
    }
}
