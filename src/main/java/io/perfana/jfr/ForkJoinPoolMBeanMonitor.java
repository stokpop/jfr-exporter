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

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.lang.management.ManagementFactory;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;

final class ForkJoinPoolMBeanMonitor implements AutoCloseable {

    static final String OBJECT_NAME = "io.perfana.jfr:type=ForkJoinPool,name=common";

    private static final Logger log = Logger.getLogger(ForkJoinPoolMBeanMonitor.class);

    private final MBeanServer mBeanServer;
    private final ForkJoinPool pool;
    private final String poolName;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    ForkJoinPoolMBeanMonitor() {
        this(ManagementFactory.getPlatformMBeanServer(), ForkJoinPool.commonPool(), "common");
    }

    ForkJoinPoolMBeanMonitor(MBeanServer mBeanServer, ForkJoinPool pool, String poolName) {
        this.mBeanServer = mBeanServer;
        this.pool = pool;
        this.poolName = poolName;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        ObjectName objectName = createObjectName();
        try {
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            mBeanServer.registerMBean(new ForkJoinPoolDynamicMBean(pool, poolName), objectName);
            log.info("Registered ForkJoinPool MBean: %s", objectName.getCanonicalName());
        } catch (Exception e) {
            log.error("Failed to register ForkJoinPool MBean for pool %s: %s", poolName, e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        ObjectName objectName = createObjectName();
        try {
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            log.info("Unregistered ForkJoinPool MBean for pool %s", poolName);
        } catch (Exception e) {
            log.error("Failed to unregister ForkJoinPool MBean for pool %s: %s", poolName, e.getMessage());
        }
    }

    private static ObjectName createObjectName() {
        try {
            return new ObjectName(OBJECT_NAME);
        } catch (MalformedObjectNameException e) {
            throw new IllegalStateException("Invalid ForkJoinPool MBean object name", e);
        }
    }

    private static final class ForkJoinPoolDynamicMBean implements DynamicMBean {

        private static final MBeanInfo MBEAN_INFO = new MBeanInfo(
                ForkJoinPoolDynamicMBean.class.getName(),
                "ForkJoinPool metrics",
                new MBeanAttributeInfo[]{
                        new MBeanAttributeInfo("PoolName", String.class.getName(), "ForkJoin pool name", true, false, false),
                        new MBeanAttributeInfo("Parallelism", Integer.TYPE.getName(), "Target parallelism level", true, false, false),
                        new MBeanAttributeInfo("PoolSize", Integer.TYPE.getName(), "Current number of worker threads", true, false, false),
                        new MBeanAttributeInfo("ActiveThreadCount", Integer.TYPE.getName(), "Workers currently stealing or executing tasks", true, false, false),
                        new MBeanAttributeInfo("RunningThreadCount", Integer.TYPE.getName(), "Workers not blocked waiting to join tasks", true, false, false),
                        new MBeanAttributeInfo("QueuedTaskCount", Long.TYPE.getName(), "Approximate number of tasks queued by worker threads", true, false, false),
                        new MBeanAttributeInfo("QueuedSubmissionCount", Integer.TYPE.getName(), "Approximate number of externally submitted tasks waiting to begin", true, false, false),
                        new MBeanAttributeInfo("StealCount", Long.TYPE.getName(), "Approximate total number of tasks stolen from one thread's queue by another", true, false, false),
                        new MBeanAttributeInfo("Quiescent", Boolean.TYPE.getName(), "Whether all workers are currently idle", true, false, true)
                },
                null,
                new MBeanOperationInfo[0],
                null
        );

        private final ForkJoinPool pool;
        private final String poolName;

        private ForkJoinPoolDynamicMBean(ForkJoinPool pool, String poolName) {
            this.pool = pool;
            this.poolName = poolName;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            if (attribute == null) {
                throw new AttributeNotFoundException("attribute must not be null");
            }
            return switch (attribute) {
                case "PoolName" -> poolName;
                case "Parallelism" -> pool.getParallelism();
                case "PoolSize" -> pool.getPoolSize();
                case "ActiveThreadCount" -> pool.getActiveThreadCount();
                case "RunningThreadCount" -> pool.getRunningThreadCount();
                case "QueuedTaskCount" -> pool.getQueuedTaskCount();
                case "QueuedSubmissionCount" -> pool.getQueuedSubmissionCount();
                case "StealCount" -> pool.getStealCount();
                case "Quiescent" -> pool.isQuiescent();
                default -> throw new AttributeNotFoundException(attribute);
            };
        }

        @Override
        public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
            throw new AttributeNotFoundException(attribute == null ? "attribute must not be null" : attribute.getName());
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            AttributeList attributeList = new AttributeList();
            if (attributes == null) {
                return attributeList;
            }
            for (String attribute : attributes) {
                try {
                    attributeList.add(new Attribute(attribute, getAttribute(attribute)));
                } catch (AttributeNotFoundException ignored) {
                    // Ignore unknown attributes to align with DynamicMBean contract.
                }
            }
            return attributeList;
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) throws ReflectionException {
            throw new ReflectionException(new NoSuchMethodException(actionName));
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            return MBEAN_INFO;
        }
    }
}
