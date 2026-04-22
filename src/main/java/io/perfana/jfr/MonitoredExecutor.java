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

import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

final class MonitoredExecutor {

    private static final String TOMCAT_THREAD_POOL_EXECUTOR_CLASS = "org.apache.tomcat.util.threads.ThreadPoolExecutor";

    private final WeakReference<Object> executorReference;
    private final String executorClass;
    private final ExecutorAccessor accessor;

    private MonitoredExecutor(Object executor, ExecutorAccessor accessor) {
        this.executorReference = new WeakReference<>(executor);
        this.executorClass = executor.getClass().getName();
        this.accessor = accessor;
    }

    static MonitoredExecutor create(Object executor) {
        if (executor instanceof ThreadPoolExecutor threadPoolExecutor) {
            return new MonitoredExecutor(threadPoolExecutor, new JdkThreadPoolExecutorAccessor());
        }
        if (executor != null && TOMCAT_THREAD_POOL_EXECUTOR_CLASS.equals(executor.getClass().getName())) {
            return new MonitoredExecutor(executor, TomcatThreadPoolExecutorAccessor.create(executor.getClass()));
        }
        return null;
    }

    String executorClass() {
        return executorClass;
    }

    boolean isScheduledExecutor() {
        Object executor = executorReference.get();
        return executor != null && accessor.isScheduledExecutor(executor);
    }

    int activeThreadCount() {
        return withExecutor(0, accessor::activeThreadCount);
    }

    int poolSize() {
        return withExecutor(0, accessor::poolSize);
    }

    int corePoolSize() {
        return withExecutor(0, accessor::corePoolSize);
    }

    int maximumPoolSize() {
        return withExecutor(0, accessor::maximumPoolSize);
    }

    int largestPoolSize() {
        return withExecutor(0, accessor::largestPoolSize);
    }

    int queueSize() {
        return withExecutor(0, accessor::queueSize);
    }

    int remainingQueueCapacity() {
        return withExecutor(0, accessor::remainingQueueCapacity);
    }

    long taskCount() {
        return withExecutor(0L, accessor::taskCount);
    }

    long completedTaskCount() {
        return withExecutor(0L, accessor::completedTaskCount);
    }

    String queueType() {
        return withExecutor("<unknown>", accessor::queueType);
    }

    boolean isTerminated() {
        return withExecutor(true, accessor::isTerminated);
    }

    private <T> T withExecutor(T defaultValue, ExecutorFunction<T> function) {
        Object executor = executorReference.get();
        if (executor == null) {
            return defaultValue;
        }
        return function.apply(executor);
    }

    @FunctionalInterface
    private interface ExecutorFunction<T> {
        T apply(Object executor);
    }

    private interface ExecutorAccessor {
        boolean isScheduledExecutor(Object executor);

        int activeThreadCount(Object executor);

        int poolSize(Object executor);

        int corePoolSize(Object executor);

        int maximumPoolSize(Object executor);

        int largestPoolSize(Object executor);

        int queueSize(Object executor);

        int remainingQueueCapacity(Object executor);

        long taskCount(Object executor);

        long completedTaskCount(Object executor);

        String queueType(Object executor);

        boolean isTerminated(Object executor);
    }

    private static final class JdkThreadPoolExecutorAccessor implements ExecutorAccessor {

        @Override
        public boolean isScheduledExecutor(Object executor) {
            return executor instanceof ScheduledThreadPoolExecutor;
        }

        @Override
        public int activeThreadCount(Object executor) {
            return threadPoolExecutor(executor).getActiveCount();
        }

        @Override
        public int poolSize(Object executor) {
            return threadPoolExecutor(executor).getPoolSize();
        }

        @Override
        public int corePoolSize(Object executor) {
            return threadPoolExecutor(executor).getCorePoolSize();
        }

        @Override
        public int maximumPoolSize(Object executor) {
            return threadPoolExecutor(executor).getMaximumPoolSize();
        }

        @Override
        public int largestPoolSize(Object executor) {
            return threadPoolExecutor(executor).getLargestPoolSize();
        }

        @Override
        public int queueSize(Object executor) {
            return queue(threadPoolExecutor(executor)).size();
        }

        @Override
        public int remainingQueueCapacity(Object executor) {
            return queue(threadPoolExecutor(executor)).remainingCapacity();
        }

        @Override
        public long taskCount(Object executor) {
            return threadPoolExecutor(executor).getTaskCount();
        }

        @Override
        public long completedTaskCount(Object executor) {
            return threadPoolExecutor(executor).getCompletedTaskCount();
        }

        @Override
        public String queueType(Object executor) {
            return queue(threadPoolExecutor(executor)).getClass().getName();
        }

        @Override
        public boolean isTerminated(Object executor) {
            return threadPoolExecutor(executor).isTerminated();
        }

        private static ThreadPoolExecutor threadPoolExecutor(Object executor) {
            return (ThreadPoolExecutor) executor;
        }

        private static BlockingQueue<Runnable> queue(ThreadPoolExecutor executor) {
            return executor.getQueue();
        }
    }

    private static final class TomcatThreadPoolExecutorAccessor implements ExecutorAccessor {

        private final Method getActiveCount;
        private final Method getPoolSize;
        private final Method getCorePoolSize;
        private final Method getMaximumPoolSize;
        private final Method getLargestPoolSize;
        private final Method getQueue;
        private final Method getTaskCount;
        private final Method getCompletedTaskCount;
        private final Method isTerminated;

        private TomcatThreadPoolExecutorAccessor(Method getActiveCount,
                                                 Method getPoolSize,
                                                 Method getCorePoolSize,
                                                 Method getMaximumPoolSize,
                                                 Method getLargestPoolSize,
                                                 Method getQueue,
                                                 Method getTaskCount,
                                                 Method getCompletedTaskCount,
                                                 Method isTerminated) {
            this.getActiveCount = getActiveCount;
            this.getPoolSize = getPoolSize;
            this.getCorePoolSize = getCorePoolSize;
            this.getMaximumPoolSize = getMaximumPoolSize;
            this.getLargestPoolSize = getLargestPoolSize;
            this.getQueue = getQueue;
            this.getTaskCount = getTaskCount;
            this.getCompletedTaskCount = getCompletedTaskCount;
            this.isTerminated = isTerminated;
        }

        static TomcatThreadPoolExecutorAccessor create(Class<?> executorClass) {
            return new TomcatThreadPoolExecutorAccessor(
                    method(executorClass, "getActiveCount"),
                    method(executorClass, "getPoolSize"),
                    method(executorClass, "getCorePoolSize"),
                    method(executorClass, "getMaximumPoolSize"),
                    method(executorClass, "getLargestPoolSize"),
                    method(executorClass, "getQueue"),
                    method(executorClass, "getTaskCount"),
                    method(executorClass, "getCompletedTaskCount"),
                    method(executorClass, "isTerminated"));
        }

        @Override
        public boolean isScheduledExecutor(Object executor) {
            return false;
        }

        @Override
        public int activeThreadCount(Object executor) {
            return invokeInt(getActiveCount, executor);
        }

        @Override
        public int poolSize(Object executor) {
            return invokeInt(getPoolSize, executor);
        }

        @Override
        public int corePoolSize(Object executor) {
            return invokeInt(getCorePoolSize, executor);
        }

        @Override
        public int maximumPoolSize(Object executor) {
            return invokeInt(getMaximumPoolSize, executor);
        }

        @Override
        public int largestPoolSize(Object executor) {
            return invokeInt(getLargestPoolSize, executor);
        }

        @Override
        public int queueSize(Object executor) {
            return queue(executor).size();
        }

        @Override
        public int remainingQueueCapacity(Object executor) {
            return queue(executor).remainingCapacity();
        }

        @Override
        public long taskCount(Object executor) {
            return invokeLong(getTaskCount, executor);
        }

        @Override
        public long completedTaskCount(Object executor) {
            return invokeLong(getCompletedTaskCount, executor);
        }

        @Override
        public String queueType(Object executor) {
            return queue(executor).getClass().getName();
        }

        @Override
        public boolean isTerminated(Object executor) {
            return invokeBoolean(isTerminated, executor);
        }

        private BlockingQueue<?> queue(Object executor) {
            Object queue = invoke(getQueue, executor);
            if (queue instanceof BlockingQueue<?> blockingQueue) {
                return blockingQueue;
            }
            throw new IllegalStateException("Tomcat executor queue is not a BlockingQueue: " + queue);
        }

        private static Method method(Class<?> type, String name) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("Missing Tomcat executor method " + name + " on " + type.getName(), e);
            }
        }

        private static int invokeInt(Method method, Object executor) {
            return ((Number) invoke(method, executor)).intValue();
        }

        private static long invokeLong(Method method, Object executor) {
            return ((Number) invoke(method, executor)).longValue();
        }

        private static boolean invokeBoolean(Method method, Object executor) {
            return (Boolean) invoke(method, executor);
        }

        private static Object invoke(Method method, Object executor) {
            try {
                return method.invoke(executor);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Failed to invoke " + method.getName() + " on " + executor.getClass().getName(), e);
            }
        }
    }
}
