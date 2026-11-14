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
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class ObjectAllocationSampleEvent implements OnJfrEvent, JfrEventProvider {

    private static final Logger log = Logger.getLogger(ObjectAllocationSampleEvent.class);

    public static final String JDK_OBJECT_ALLOCATION_SAMPLE = "jdk.ObjectAllocationSample";
    private final JfrEventProcessor eventProcessor;
    private final long bigAllocationSizeBytes;

    private final AtomicLong totalAllocationsBytes = new AtomicLong(0);
    private final AtomicLong lastAllocationRateReport = new AtomicLong(0);

    private static final long reportIntervalMs = 2000;

    private static final long KiB = 1_024L;
    private static final long MiB = 1_024L * KiB;
    private static final long GiB = 1_024L * MiB;

    private static final Map<String, String> TAGS_LT_1KiB      = Map.of("size-bucket", "<1KiB");
    private static final Map<String, String> TAGS_1_10KiB      = Map.of("size-bucket", "1-10KiB");
    private static final Map<String, String> TAGS_10_100KiB    = Map.of("size-bucket", "10-100KiB");
    private static final Map<String, String> TAGS_100KiB_1MiB  = Map.of("size-bucket", "100KiB-1MiB");
    private static final Map<String, String> TAGS_1_10MiB      = Map.of("size-bucket", "1-10MiB");
    private static final Map<String, String> TAGS_10_100MiB    = Map.of("size-bucket", "10-100MiB");
    private static final Map<String, String> TAGS_100MiB_1GiB  = Map.of("size-bucket", "100MiB-1GiB");
    private static final Map<String, String> TAGS_GT_1GiB      = Map.of("size-bucket", ">1GiB");

    public ObjectAllocationSampleEvent(JfrEventProcessor eventProcessor, long thresholdSizeBytes) {
        if (eventProcessor == null) throw new IllegalArgumentException("eventProcessor must not be null");
        log.debug("Tracing object allocations of more than %d bytes.", thresholdSizeBytes);
        this.eventProcessor = eventProcessor;
        this.bigAllocationSizeBytes = thresholdSizeBytes;
    }

    @Override
    public void onEvent(RecordedEvent event) {
        String name = event.getEventType().getName();
        // The relative weight of the sample. Aggregating the weights for a large number of samples,
        // for a particular class, thread or stack trace,
        // gives a statistically accurate representation of the allocation pressure
        long weight = event.getLong("weight");
        RecordedClass recordedClass = event.getClass("objectClass");
        if (recordedClass == null) {
            log.error("No objectClass in %s event, skipping", name);
            return;
        }
        String objectClass = recordedClass.getName();
        Instant startTime = event.getStartTime();
        log.trace("%s %s %d", (startTime == null ? "<no-start-time>" : startTime), name, weight);

        reportLargeAllocationSample(event, weight, objectClass, startTime);

        reportTotalAllocations(weight);
    }

    private void reportTotalAllocations(long weight) {
        totalAllocationsBytes.addAndGet(weight);

        long now = System.currentTimeMillis();

        long timePeriodMs = now - lastAllocationRateReport.get();

        if (timePeriodMs > reportIntervalMs) {

            lastAllocationRateReport.set(now);

            long totalAllocations = totalAllocationsBytes.getAndSet(0);

            long allocationRate = totalAllocations / (timePeriodMs / 1000);

            log.debug("Total allocations: %d bytes, allocation rate: %d bytes/s",
                    totalAllocations,
                    allocationRate);

            ProcessedJfrEvent rateEvent = ProcessedJfrEvent.of(
                    Instant.ofEpochMilli(now),
                    "allocation-rate-bytes",
                    "bytes",
                    allocationRate);

            eventProcessor.processEvent(rateEvent);
        }
    }

    private void reportLargeAllocationSample(RecordedEvent event, long weight, String objectClass, Instant startTime) {
        if (weight > bigAllocationSizeBytes) {

            if (event.getStackTrace() == null) {
                log.error("No stack trace available for large allocation sample weight of %d bytes of objectClass '%s'", weight, objectClass);
                return;
            }

            List<String> stackTrace = JfrUtil.translateStacktrace(event);

            String objectClassTranslation = JfrUtil.translatePrimitiveClass(objectClass);
            String firstStack = stackTrace.isEmpty() ? "<none>" : stackTrace.get(0);
            log.debug("Found high allocation weight of %d bytes of %s in '%s'", weight, objectClassTranslation, firstStack);

            Map<String, Object> extraFields = Map.of("objectClass", objectClassTranslation, "thread", JfrUtil.nullSafeGetThreadJavaName(event));

            ProcessedJfrEvent processedEvent = ProcessedJfrEvent.of(
                    startTime,
                    "object-allocation-sample",
                    sizeBucketTags(weight),
                    "bytes",
                    weight,
                    extraFields,
                    stackTrace);

            eventProcessor.processEvent(processedEvent);
        }
    }

    static Map<String, String> sizeBucketTags(long bytes) {
        if (bytes < KiB)        return TAGS_LT_1KiB;
        if (bytes < 10 * KiB)   return TAGS_1_10KiB;
        if (bytes < 100 * KiB)  return TAGS_10_100KiB;
        if (bytes < MiB)        return TAGS_100KiB_1MiB;
        if (bytes < 10 * MiB)   return TAGS_1_10MiB;
        if (bytes < 100 * MiB)  return TAGS_10_100MiB;
        if (bytes < GiB)        return TAGS_100MiB_1GiB;
        return TAGS_GT_1GiB;
    }

    static String sizeBucket(long bytes) {
        return sizeBucketTags(bytes).get("size-bucket");
    }

    @Override
    public List<JfrEventSettings> getEventSettings() {
        JfrEventSettings settings = JfrEventSettings.of(JDK_OBJECT_ALLOCATION_SAMPLE, this).withThreshold("throttle", "200/s");
        return List.of(settings);
    }
}
