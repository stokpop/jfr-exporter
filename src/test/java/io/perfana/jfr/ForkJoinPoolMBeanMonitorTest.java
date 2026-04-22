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

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.AttributeNotFoundException;
import javax.management.ObjectName;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForkJoinPoolMBeanMonitorTest {

    @Test
    void registersForkJoinPoolMBeanAndExposesMetrics() throws Exception {
        MBeanServer mBeanServer = MBeanServerFactory.createMBeanServer();
        ForkJoinPool pool = new ForkJoinPool(2);

        try (ForkJoinPoolMBeanMonitor monitor = new ForkJoinPoolMBeanMonitor(mBeanServer, pool, "test")) {
            monitor.start();
            pool.submit(() -> { });
            assertTrue(pool.awaitQuiescence(5, TimeUnit.SECONDS));

            Set<ObjectName> objectNames = mBeanServer.queryNames(new ObjectName(ForkJoinPoolMBeanMonitor.OBJECT_NAME), null);
            assertEquals(1, objectNames.size());

            ObjectName objectName = objectNames.iterator().next();
            assertEquals("test", mBeanServer.getAttribute(objectName, "PoolName"));
            assertEquals(pool.getParallelism(), mBeanServer.getAttribute(objectName, "Parallelism"));
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "PoolSize") >= 0);
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "ActiveThreadCount") >= 0);
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "RunningThreadCount") >= 0);
            assertTrue((Long) mBeanServer.getAttribute(objectName, "QueuedTaskCount") >= 0L);
            assertTrue((Integer) mBeanServer.getAttribute(objectName, "QueuedSubmissionCount") >= 0);
            assertTrue((Long) mBeanServer.getAttribute(objectName, "StealCount") >= 0L);
            assertTrue((Boolean) mBeanServer.getAttribute(objectName, "Quiescent"));
            assertThrows(AttributeNotFoundException.class, () -> mBeanServer.getAttribute(objectName, "AsyncMode"));
            assertThrows(AttributeNotFoundException.class, () -> mBeanServer.getAttribute(objectName, "Terminated"));
        } finally {
            pool.shutdownNow();
            MBeanServerFactory.releaseMBeanServer(mBeanServer);
        }
    }

    @Test
    void closeUnregistersForkJoinPoolMBean() throws Exception {
        MBeanServer mBeanServer = MBeanServerFactory.createMBeanServer();
        ForkJoinPool pool = new ForkJoinPool(2);
        ForkJoinPoolMBeanMonitor monitor = new ForkJoinPoolMBeanMonitor(mBeanServer, pool, "test");

        try {
            monitor.start();
            assertEquals(1, mBeanServer.queryNames(new ObjectName(ForkJoinPoolMBeanMonitor.OBJECT_NAME), null).size());

            monitor.close();

            assertEquals(0, mBeanServer.queryNames(new ObjectName(ForkJoinPoolMBeanMonitor.OBJECT_NAME), null).size());
        } finally {
            pool.shutdownNow();
            MBeanServerFactory.releaseMBeanServer(mBeanServer);
        }
    }
}

