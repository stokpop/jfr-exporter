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
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class DiscoveredExecutorMBeanMonitor implements AutoCloseable {

    static final String OBJECT_NAME_PREFIX = "io.perfana.jfr:type=ExecutorPool";

    private static final Logger log = Logger.getLogger(DiscoveredExecutorMBeanMonitor.class);
    private static final Duration DEFAULT_SYNC_INTERVAL = Duration.ofSeconds(1);

    private final MBeanServer mBeanServer;
    private final Duration syncInterval;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ObjectName> registeredObjectNames = new HashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    DiscoveredExecutorMBeanMonitor() {
        this(ManagementFactory.getPlatformMBeanServer(), DEFAULT_SYNC_INTERVAL, createScheduler());
    }

    DiscoveredExecutorMBeanMonitor(MBeanServer mBeanServer,
                                   Duration syncInterval,
                                   ScheduledExecutorService scheduler) {
        this.mBeanServer = mBeanServer;
        this.syncInterval = syncInterval;
        this.scheduler = scheduler;

        if (syncInterval.isZero() || syncInterval.isNegative()) {
            throw new IllegalArgumentException("syncInterval must be positive");
        }
    }

    public void start() {
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::syncSafely, 0, syncInterval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    void sync() {
        Map<String, DiscoveredExecutorRegistry.RegisteredExecutor> executorsByPool = new HashMap<>();
        for (DiscoveredExecutorRegistry.RegisteredExecutor registeredExecutor : DiscoveredExecutorRegistry.snapshot()) {
            executorsByPool.put(registeredExecutor.registration().poolName(), registeredExecutor);
        }

        unregisterMissing(executorsByPool.keySet());
        executorsByPool.values().forEach(this::registerIfMissing);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        registeredObjectNames.entrySet().removeIf(entry -> {
            unregister(entry.getKey(), entry.getValue());
            return true;
        });
    }

    private void registerIfMissing(DiscoveredExecutorRegistry.RegisteredExecutor registeredExecutor) {
        String poolName = registeredExecutor.registration().poolName();
        if (registeredObjectNames.containsKey(poolName)) {
            return;
        }

        ObjectName objectName = createObjectName(poolName);
        try {
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            mBeanServer.registerMBean(new ExecutorPoolDynamicMBean(registeredExecutor), objectName);
            registeredObjectNames.put(poolName, objectName);
            log.info("Registered executor MBean: %s", objectName.getCanonicalName());
        } catch (Exception e) {
            log.error("Failed to register executor MBean for pool %s: %s", poolName, e.getMessage());
        }
    }

    private void unregisterMissing(Set<String> activePools) {
        registeredObjectNames.entrySet().removeIf(entry -> {
            if (activePools.contains(entry.getKey())) {
                return false;
            }
            unregister(entry.getKey(), entry.getValue());
            return true;
        });
    }

    private void unregister(String poolName, ObjectName objectName) {
        try {
            if (mBeanServer.isRegistered(objectName)) {
                mBeanServer.unregisterMBean(objectName);
            }
            log.info("Unregistered executor MBean for pool %s", poolName);
        } catch (Exception e) {
            log.error("Failed to unregister executor MBean for pool %s: %s", poolName, e.getMessage());
        }
    }

    private void syncSafely() {
        try {
            sync();
        } catch (RuntimeException e) {
            log.error("Failed to synchronize executor MBeans: %s", e.getMessage());
        }
    }

    private static ObjectName createObjectName(String poolName) {
        try {
            return new ObjectName(OBJECT_NAME_PREFIX + ",name=" + ObjectName.quote(poolName));
        } catch (MalformedObjectNameException e) {
            throw new IllegalArgumentException("Invalid pool name for MBean registration: " + poolName, e);
        }
    }

    private static ScheduledExecutorService createScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("jfr-exporter-executor-mbeans");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    private static final class ExecutorPoolDynamicMBean implements DynamicMBean {

        private static final MBeanInfo MBEAN_INFO = new MBeanInfo(
                ExecutorPoolDynamicMBean.class.getName(),
                "Discovered executor metrics",
                new MBeanAttributeInfo[]{
                        new MBeanAttributeInfo("PoolName", String.class.getName(), "Unique discovered executor pool name", true, false, false),
                        new MBeanAttributeInfo("Origin", String.class.getName(), "Call site where the executor was discovered", true, false, false),
                        new MBeanAttributeInfo("ExecutorClass", String.class.getName(), "Executor implementation class", true, false, false),
                        new MBeanAttributeInfo("QueueType", String.class.getName(), "Queue implementation class", true, false, false),
                        new MBeanAttributeInfo("IsScheduledExecutor", Boolean.TYPE.getName(), "Whether this executor is a ScheduledThreadPoolExecutor", true, false, true),
                        new MBeanAttributeInfo("ActiveThreadCount", Integer.TYPE.getName(), "Threads currently executing tasks", true, false, false),
                        new MBeanAttributeInfo("PoolSize", Integer.TYPE.getName(), "Current number of threads in the pool", true, false, false),
                        new MBeanAttributeInfo("CorePoolSize", Integer.TYPE.getName(), "Configured core pool size", true, false, false),
                        new MBeanAttributeInfo("MaximumPoolSize", Integer.TYPE.getName(), "Configured maximum pool size", true, false, false),
                        new MBeanAttributeInfo("LargestPoolSize", Integer.TYPE.getName(), "Largest number of threads ever simultaneously in the pool", true, false, false),
                        new MBeanAttributeInfo("QueueSize", Integer.TYPE.getName(), "Current number of queued tasks; for scheduled executors this may include delayed tasks waiting for their trigger time", true, false, false),
                        new MBeanAttributeInfo("RemainingQueueCapacity", Integer.TYPE.getName(), "Remaining queue capacity if bounded; for unbounded queues this may be very large", true, false, false),
                        new MBeanAttributeInfo("TaskCount", Long.TYPE.getName(), "Approximate total number of tasks ever scheduled for execution", true, false, false),
                        new MBeanAttributeInfo("CompletedTaskCount", Long.TYPE.getName(), "Approximate total number of tasks completed", true, false, false),
                        new MBeanAttributeInfo("Terminated", Boolean.TYPE.getName(), "Whether the executor has terminated", true, false, true)
                },
                null,
                new MBeanOperationInfo[0],
                null
        );

        private final WeakReference<ThreadPoolExecutor> executorReference;
        private final DiscoveredExecutorRegistry.ExecutorRegistration registration;

        private ExecutorPoolDynamicMBean(DiscoveredExecutorRegistry.RegisteredExecutor registeredExecutor) {
            this.executorReference = new WeakReference<>(registeredExecutor.executor());
            this.registration = registeredExecutor.registration();
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            if (attribute == null) {
                throw new AttributeNotFoundException("attribute must not be null");
            }
            return switch (attribute) {
                case "PoolName" -> registration.poolName();
                case "Origin" -> registration.origin();
                case "ExecutorClass" -> registration.executorClass();
                case "QueueType" -> queueType();
                case "IsScheduledExecutor" -> isScheduledExecutor();
                case "ActiveThreadCount" -> activeThreadCount();
                case "PoolSize" -> poolSize();
                case "CorePoolSize" -> corePoolSize();
                case "MaximumPoolSize" -> maximumPoolSize();
                case "LargestPoolSize" -> largestPoolSize();
                case "QueueSize" -> queueSize();
                case "RemainingQueueCapacity" -> remainingQueueCapacity();
                case "TaskCount" -> taskCount();
                case "CompletedTaskCount" -> completedTaskCount();
                case "Terminated" -> terminated();
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

        private int activeThreadCount() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0 : executor.getActiveCount();
        }

        private int poolSize() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0 : executor.getPoolSize();
        }

        private int queueSize() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0 : executor.getQueue().size();
        }

        private String queueType() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? "<unknown>" : queue(executor).getClass().getName();
        }

        private boolean isScheduledExecutor() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor instanceof ScheduledThreadPoolExecutor;
        }

        private int corePoolSize() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0 : executor.getCorePoolSize();
        }

        private int maximumPoolSize() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0 : executor.getMaximumPoolSize();
        }

        private int largestPoolSize() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0 : executor.getLargestPoolSize();
        }

        private int remainingQueueCapacity() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0 : queue(executor).remainingCapacity();
        }

        private long taskCount() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0L : executor.getTaskCount();
        }

        private long completedTaskCount() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null ? 0L : executor.getCompletedTaskCount();
        }

        private boolean terminated() {
            ThreadPoolExecutor executor = executorReference.get();
            return executor == null || executor.isTerminated();
        }

        private static BlockingQueue<Runnable> queue(ThreadPoolExecutor executor) {
            return executor.getQueue();
        }
    }
}


