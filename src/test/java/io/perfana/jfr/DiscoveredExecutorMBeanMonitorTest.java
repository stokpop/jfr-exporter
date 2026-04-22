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
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class DiscoveredExecutorMBeanMonitorTest {
    @AfterEach
    void clearRegistry() {
        DiscoveredExecutorRegistry.clear();
    }
    @Test
    void registersMBeanForDiscoveredExecutorAndExposesMetrics() throws Exception {
        MBeanServer mBeanServer = MBeanServerFactory.createMBeanServer();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (DiscoveredExecutorMBeanMonitor monitor = new DiscoveredExecutorMBeanMonitor(mBeanServer, Duration.ofSeconds(1), scheduler)) {
            DiscoveredExecutorRegistry.register(executor);
            executor.submit(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.submit(() -> { });
            assertTrue(running.await(5, TimeUnit.SECONDS));
            monitor.sync();
            Set<ObjectName> objectNames = mBeanServer.queryNames(new ObjectName(DiscoveredExecutorMBeanMonitor.OBJECT_NAME_PREFIX + ",*"), null);
            assertEquals(1, objectNames.size());
            ObjectName objectName = objectNames.iterator().next();
            assertEquals(executor.getQueue().getClass().getName(), mBeanServer.getAttribute(objectName, "QueueType"));
            assertFalse((Boolean) mBeanServer.getAttribute(objectName, "IsScheduledExecutor"));
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "ActiveThreadCount") >= 1);
            assertEquals(executor.getCorePoolSize(), mBeanServer.getAttribute(objectName, "CorePoolSize"));
            assertEquals(executor.getMaximumPoolSize(), mBeanServer.getAttribute(objectName, "MaximumPoolSize"));
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "LargestPoolSize") >= 1);
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "QueueSize") >= 1);
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "RemainingQueueCapacity") >= 0);
            assertTrue((Long) mBeanServer.getAttribute(objectName, "TaskCount") >= 2L);
            assertTrue((Long) mBeanServer.getAttribute(objectName, "CompletedTaskCount") >= 0L);
            assertEquals(executor.getClass().getName(), mBeanServer.getAttribute(objectName, "ExecutorClass"));
            assertFalse((Boolean) mBeanServer.getAttribute(objectName, "Terminated"));
        } finally {
            release.countDown();
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            MBeanServerFactory.releaseMBeanServer(mBeanServer);
        }
    }
    @Test
    void unregistersMBeanWhenExecutorTerminates() throws Exception {
        MBeanServer mBeanServer = MBeanServerFactory.createMBeanServer();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try (DiscoveredExecutorMBeanMonitor monitor = new DiscoveredExecutorMBeanMonitor(mBeanServer, Duration.ofSeconds(1), scheduler)) {
            DiscoveredExecutorRegistry.register(executor);
            monitor.sync();
            assertEquals(1, mBeanServer.queryNames(new ObjectName(DiscoveredExecutorMBeanMonitor.OBJECT_NAME_PREFIX + ",*"), null).size());
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            monitor.sync();
            assertEquals(0, mBeanServer.queryNames(new ObjectName(DiscoveredExecutorMBeanMonitor.OBJECT_NAME_PREFIX + ",*"), null).size());
        } finally {
            executor.shutdownNow();
            MBeanServerFactory.releaseMBeanServer(mBeanServer);
        }
    }

    @Test
    void exposesScheduledExecutorHints() throws Exception {
        MBeanServer mBeanServer = MBeanServerFactory.createMBeanServer();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);

        try (DiscoveredExecutorMBeanMonitor monitor = new DiscoveredExecutorMBeanMonitor(mBeanServer, Duration.ofSeconds(1), scheduler)) {
            DiscoveredExecutorRegistry.register(executor);
            executor.schedule(() -> { }, 1, TimeUnit.MINUTES);
            monitor.sync();

            ObjectName objectName = mBeanServer.queryNames(new ObjectName(DiscoveredExecutorMBeanMonitor.OBJECT_NAME_PREFIX + ",*"), null)
                    .iterator().next();

            assertTrue((Boolean) mBeanServer.getAttribute(objectName, "IsScheduledExecutor"));
            assertEquals(executor.getQueue().getClass().getName(), mBeanServer.getAttribute(objectName, "QueueType"));
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "QueueSize") >= 1);
            assertTrue((Long) mBeanServer.getAttribute(objectName, "TaskCount") >= 1L);
        } finally {
            executor.shutdownNow();
            MBeanServerFactory.releaseMBeanServer(mBeanServer);
        }
    }
}
