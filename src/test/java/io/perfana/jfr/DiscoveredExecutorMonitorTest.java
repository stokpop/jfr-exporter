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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveredExecutorMonitorTest {

    @AfterEach
    void clearRegistry() {
        DiscoveredExecutorRegistry.clear();
    }

    @Test
    void samplesRegisteredExecutors() {
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, Duration.ofSeconds(1), scheduler)) {
            DiscoveredExecutorRegistry.register(executor);
            monitor.sample();

            assertEquals(1, captured.size());
            ProcessedJfrEvent event = captured.get(0);
            assertEquals("executor-pool", event.measurementName());
            assertEquals("activeThreadCount", event.field());
            assertTrue(event.tags().containsKey("pool"));
            assertTrue(event.tags().containsKey("origin"));
            assertEquals(executor.getClass().getName(), event.tags().get("executorClass"));
            assertTrue(event.extraFields().containsKey("poolSize"));
            assertTrue(event.extraFields().containsKey("queueSize"));
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void samplesQueuedAndActiveExecutorTasks() throws Exception {
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, Duration.ofSeconds(1), scheduler)) {
            DiscoveredExecutorRegistry.register(executor);
            executor.submit(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit(() -> {});

            assertTrue(running.await(5, TimeUnit.SECONDS));
            monitor.sample();

            ProcessedJfrEvent event = captured.get(0);
            assertEquals(1, event.value().intValue());
            assertTrue(((Number) event.extraFields().get("queueSize")).longValue() >= 1L);
        } finally {
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void startSchedulesSampling() throws InterruptedException {
        List<ProcessedJfrEvent> captured = new ArrayList<>();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, Duration.ofMillis(25), scheduler)) {
            DiscoveredExecutorRegistry.register(executor);
            monitor.start();
            Thread.sleep(80);

            assertFalse(captured.isEmpty());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void hooksRegisterExecutorsCreatedThroughFactories() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        List<ProcessedJfrEvent> captured = new ArrayList<>();

        java.util.concurrent.ExecutorService executor = ExecutorInstrumentationHooks.newFixedThreadPool(1);
        try (DiscoveredExecutorMonitor monitor = new DiscoveredExecutorMonitor(captured::add, Duration.ofSeconds(1), scheduler)) {
            monitor.sample();

            assertEquals(1, captured.size());
        } finally {
            executor.shutdown();
        }
    }
}
