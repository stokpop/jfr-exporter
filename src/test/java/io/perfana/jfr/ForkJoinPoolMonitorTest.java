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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForkJoinPoolMonitorTest {

    @Test
    void samplePublishesCommonPoolMetricsAsProcessedEvent() {
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ForkJoinPool pool = new ForkJoinPool(2);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try (ForkJoinPoolMonitor monitor = new ForkJoinPoolMonitor(
                captured::add,
                pool,
                java.util.Map.of("pool", "test"),
                Duration.ofSeconds(1),
                scheduler)) {
            monitor.sample();

            assertEquals(1, captured.size());
            ProcessedJfrEvent event = captured.get(0);
            assertEquals("forkjoin-pool", event.measurementName());
            assertEquals("activeThreadCount", event.field());
            assertEquals("test", event.tags().get("pool"));
            assertTrue(event.extraFields().containsKey("poolSize"));
            assertTrue(event.extraFields().containsKey("queuedTaskCount"));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void closeStopsScheduler() {
        ForkJoinPool pool = new ForkJoinPool(2);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ForkJoinPoolMonitor monitor = new ForkJoinPoolMonitor(
                event -> {},
                pool,
                java.util.Map.of("pool", "test"),
                Duration.ofSeconds(1),
                scheduler);

        try {
            monitor.close();
            assertTrue(scheduler.isShutdown());
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void startSchedulesSampling() throws InterruptedException {
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ForkJoinPool pool = new ForkJoinPool(2);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        try (ForkJoinPoolMonitor monitor = new ForkJoinPoolMonitor(
                captured::add,
                pool,
                java.util.Map.of("pool", "test"),
                Duration.ofMillis(25),
                scheduler)) {
            monitor.start();

            Thread.sleep(80);

            assertFalse(captured.isEmpty());
        } finally {
            pool.shutdown();
        }
    }
}
