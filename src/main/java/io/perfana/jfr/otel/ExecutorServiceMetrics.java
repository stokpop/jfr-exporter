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
package io.perfana.jfr.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.perfana.jfr.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Registers observable gauges for an {@link ExecutorService} backed by a
 * {@link ThreadPoolExecutor}.  Three metrics are reported per pool:
 * <ul>
 *   <li>{@code jfr.executor.threads.active} – threads currently executing tasks</li>
 *   <li>{@code jfr.executor.threads.total}  – current pool size (all threads)</li>
 *   <li>{@code jfr.executor.queue.size}     – tasks waiting in the work queue</li>
 * </ul>
 * Every metric carries a {@code pool} attribute set to the supplied name so
 * multiple pools can be distinguished in dashboards.
 *
 * <p>This class is intentionally free of JFR-specific concerns so it can be
 * extracted into a reusable library alongside {@link JfrMeterProvider}.
 */
public final class ExecutorServiceMetrics implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ExecutorServiceMetrics.class);

    private static final String METRIC_ACTIVE_THREADS = "jfr.executor.threads.active";
    private static final String METRIC_TOTAL_THREADS  = "jfr.executor.threads.total";
    private static final String METRIC_QUEUE_SIZE     = "jfr.executor.queue.size";

    private final List<AutoCloseable> handles;

    private ExecutorServiceMetrics(List<AutoCloseable> handles) {
        this.handles = List.copyOf(handles);
    }

    /**
     * Registers pool metrics for {@code executor} using {@code poolName} as the
     * {@code pool} attribute value.  If {@code executor} is not a
     * {@link ThreadPoolExecutor} (e.g. a virtual-thread executor), a no-op instance
     * is returned and a warning is logged.
     */
    public static ExecutorServiceMetrics register(JfrMeterProvider meterProvider,
                                                  ExecutorService executor,
                                                  String poolName) {
        Objects.requireNonNull(poolName, "poolName must not be null");
        return register(meterProvider, executor,
                Attributes.of(AttributeKey.stringKey("pool"), poolName));
    }

    /**
     * Registers pool metrics for {@code executor} tagging every gauge with
     * {@code attributes}.
     */
    public static ExecutorServiceMetrics register(JfrMeterProvider meterProvider,
                                                  ExecutorService executor,
                                                  Attributes attributes) {
        Objects.requireNonNull(meterProvider, "meterProvider must not be null");
        Objects.requireNonNull(executor,      "executor must not be null");
        Objects.requireNonNull(attributes,    "attributes must not be null");

        if (!(executor instanceof ThreadPoolExecutor tpe)) {
            log.info("ExecutorServiceMetrics: executor is not a ThreadPoolExecutor (%s), skipping metrics registration.",
                    executor.getClass().getName());
            return new ExecutorServiceMetrics(List.of());
        }

        List<AutoCloseable> handles = new ArrayList<>(3);
        handles.add(meterProvider.registerLongGauge(
                new JfrMeterProvider.MetricDescriptor(METRIC_ACTIVE_THREADS,
                        "Number of threads actively executing tasks in the pool.", "1"),
                attributes,
                tpe::getActiveCount));

        handles.add(meterProvider.registerLongGauge(
                new JfrMeterProvider.MetricDescriptor(METRIC_TOTAL_THREADS,
                        "Current number of threads in the pool.", "1"),
                attributes,
                tpe::getPoolSize));

        handles.add(meterProvider.registerLongGauge(
                new JfrMeterProvider.MetricDescriptor(METRIC_QUEUE_SIZE,
                        "Number of tasks waiting in the executor work queue.", "1"),
                attributes,
                () -> tpe.getQueue().size()));

        return new ExecutorServiceMetrics(handles);
    }

    /** Deregisters all gauges associated with this instance. */
    @Override
    public void close() throws Exception {
        Exception first = null;
        for (AutoCloseable handle : handles) {
            try {
                handle.close();
            } catch (Exception e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }
}
