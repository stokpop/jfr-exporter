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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

final class DiscoveredExecutorRegistry {

    private static final Logger log = Logger.getLogger(DiscoveredExecutorRegistry.class);
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final Map<ThreadPoolExecutor, ExecutorRegistration> EXECUTORS = new WeakHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private DiscoveredExecutorRegistry() {
    }

    static <T extends ExecutorService> T register(T executor) {
        if (!(executor instanceof ThreadPoolExecutor threadPoolExecutor)) {
            return executor;
        }

        synchronized (EXECUTORS) {
            if (!EXECUTORS.containsKey(threadPoolExecutor)) {
                String origin = callerOrigin();
                String poolName = poolName(origin);
                EXECUTORS.put(threadPoolExecutor, new ExecutorRegistration(poolName, origin, threadPoolExecutor.getClass().getName()));
                log.info("Registered executor for monitoring: pool=%s, origin=%s", poolName, origin);
            }
        }

        return executor;
    }

    static List<RegisteredExecutor> snapshot() {
        List<RegisteredExecutor> registrations = new ArrayList<>();
        synchronized (EXECUTORS) {
            EXECUTORS.entrySet().removeIf(entry -> entry.getKey().isTerminated());
            EXECUTORS.forEach((executor, registration) -> registrations.add(new RegisteredExecutor(executor, registration)));
        }
        return registrations;
    }

    static void clear() {
        synchronized (EXECUTORS) {
            EXECUTORS.clear();
        }
        NEXT_ID.set(1);
    }

    private static String callerOrigin() {
        return STACK_WALKER.walk(frames -> frames
                .filter(frame -> !frame.getClassName().startsWith("io.perfana.jfr.")
                        && !frame.getClassName().startsWith("java.util.concurrent.Executors"))
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName())
                .orElse("unknown"));
    }

    private static String poolName(String origin) {
        String[] parts = origin.split("#", 2);
        String simpleClassName = parts[0].substring(parts[0].lastIndexOf('.') + 1);
        String methodName = parts.length == 2 ? parts[1] : "executor";
        return sanitize(simpleClassName) + "-" + sanitize(methodName) + "-" + NEXT_ID.getAndIncrement();
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9_.-]", "-").replaceAll("-{2,}", "-");
        return sanitized.isBlank() ? "executor" : sanitized;
    }

    record RegisteredExecutor(ThreadPoolExecutor executor, ExecutorRegistration registration) {
    }

    record ExecutorRegistration(String poolName, String origin, String executorClass) {
    }
}
