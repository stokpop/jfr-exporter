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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ExecutorInstrumentationHooks {

    private ExecutorInstrumentationHooks() {
    }

    public static ExecutorService newFixedThreadPool(int nThreads) {
        return register(new ThreadPoolExecutor(
                nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()));
    }

    public static ExecutorService newFixedThreadPool(int nThreads, ThreadFactory threadFactory) {
        return register(new ThreadPoolExecutor(
                nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadFactory));
    }

    public static ExecutorService newSingleThreadExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        return Executors.unconfigurableExecutorService(register(executor));
    }

    public static ExecutorService newSingleThreadExecutor(ThreadFactory threadFactory) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), threadFactory);
        return Executors.unconfigurableExecutorService(register(executor));
    }

    public static ExecutorService newCachedThreadPool() {
        return register(new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>()));
    }

    public static ExecutorService newCachedThreadPool(ThreadFactory threadFactory) {
        return register(new ThreadPoolExecutor(
                0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new java.util.concurrent.SynchronousQueue<>(), threadFactory));
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
        return Executors.unconfigurableScheduledExecutorService(register(executor));
    }

    public static ScheduledExecutorService newSingleThreadScheduledExecutor(ThreadFactory threadFactory) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        return Executors.unconfigurableScheduledExecutorService(register(executor));
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize) {
        return register(new ScheduledThreadPoolExecutor(corePoolSize));
    }

    public static ScheduledExecutorService newScheduledThreadPool(int corePoolSize, ThreadFactory threadFactory) {
        return register(new ScheduledThreadPoolExecutor(corePoolSize, threadFactory));
    }

    public static ThreadPoolExecutor newThreadPoolExecutor(int corePoolSize,
                                                           int maximumPoolSize,
                                                           long keepAliveTime,
                                                           TimeUnit unit,
                                                           BlockingQueue<Runnable> workQueue) {
        return register(new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue));
    }

    public static ThreadPoolExecutor newThreadPoolExecutor(int corePoolSize,
                                                           int maximumPoolSize,
                                                           long keepAliveTime,
                                                           TimeUnit unit,
                                                           BlockingQueue<Runnable> workQueue,
                                                           ThreadFactory threadFactory) {
        return register(new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory));
    }

    public static ThreadPoolExecutor newThreadPoolExecutor(int corePoolSize,
                                                           int maximumPoolSize,
                                                           long keepAliveTime,
                                                           TimeUnit unit,
                                                           BlockingQueue<Runnable> workQueue,
                                                           RejectedExecutionHandler handler) {
        return register(new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, handler));
    }

    public static ThreadPoolExecutor newThreadPoolExecutor(int corePoolSize,
                                                           int maximumPoolSize,
                                                           long keepAliveTime,
                                                           TimeUnit unit,
                                                           BlockingQueue<Runnable> workQueue,
                                                           ThreadFactory threadFactory,
                                                           RejectedExecutionHandler handler) {
        return register(new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler));
    }

    public static ScheduledThreadPoolExecutor newScheduledThreadPoolExecutor(int corePoolSize) {
        return register(new ScheduledThreadPoolExecutor(corePoolSize));
    }

    public static ScheduledThreadPoolExecutor newScheduledThreadPoolExecutor(int corePoolSize,
                                                                             ThreadFactory threadFactory) {
        return register(new ScheduledThreadPoolExecutor(corePoolSize, threadFactory));
    }

    public static ScheduledThreadPoolExecutor newScheduledThreadPoolExecutor(int corePoolSize,
                                                                             RejectedExecutionHandler handler) {
        return register(new ScheduledThreadPoolExecutor(corePoolSize, handler));
    }

    public static ScheduledThreadPoolExecutor newScheduledThreadPoolExecutor(int corePoolSize,
                                                                             ThreadFactory threadFactory,
                                                                             RejectedExecutionHandler handler) {
        return register(new ScheduledThreadPoolExecutor(corePoolSize, threadFactory, handler));
    }

    private static <T extends ThreadPoolExecutor> T register(T executor) {
        return DiscoveredExecutorRegistry.register(executor);
    }
}
