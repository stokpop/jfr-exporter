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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;

/**
 * Registers observable gauges for a {@link ForkJoinPool}.  Three metrics are
 * reported per pool:
 * <ul>
 *   <li>{@code jfr.forkjoin.threads.active} – threads actively executing fork/join tasks</li>
 *   <li>{@code jfr.forkjoin.pool.size}       – current number of threads in the pool</li>
 *   <li>{@code jfr.forkjoin.tasks.queued}    – number of queued subtasks</li>
 * </ul>
 * Every metric carries a {@code pool} attribute (e.g. {@code "common"}) so
 * multiple pools can be distinguished in dashboards.
 *
 * <p>This class is intentionally free of JFR-specific concerns so it can be
 * extracted into a reusable library alongside {@link JfrMeterProvider}.
 */
public final class ForkJoinPoolMetrics implements AutoCloseable {

    private static final String METRIC_ACTIVE_THREADS = "jfr.forkjoin.threads.active";
    private static final String METRIC_POOL_SIZE       = "jfr.forkjoin.pool.size";
    private static final String METRIC_TASKS_QUEUED    = "jfr.forkjoin.tasks.queued";

    private final List<AutoCloseable> handles;

    private ForkJoinPoolMetrics(List<AutoCloseable> handles) {
        this.handles = List.copyOf(handles);
    }

    /**
     * Registers metrics for {@link ForkJoinPool#commonPool()} with
     * {@code pool=common} attribute.
     */
    public static ForkJoinPoolMetrics registerCommonPool(JfrMeterProvider meterProvider) {
        return register(meterProvider, ForkJoinPool.commonPool(), "common");
    }

    /**
     * Registers pool metrics for {@code pool} using {@code poolName} as the
     * {@code pool} attribute value.
     */
    public static ForkJoinPoolMetrics register(JfrMeterProvider meterProvider,
                                               ForkJoinPool pool,
                                               String poolName) {
        Objects.requireNonNull(poolName, "poolName must not be null");
        return register(meterProvider, pool,
                Attributes.of(AttributeKey.stringKey("pool"), poolName));
    }

    /**
     * Registers pool metrics for {@code pool} tagging every gauge with
     * {@code attributes}.
     */
    public static ForkJoinPoolMetrics register(JfrMeterProvider meterProvider,
                                               ForkJoinPool pool,
                                               Attributes attributes) {
        Objects.requireNonNull(meterProvider, "meterProvider must not be null");
        Objects.requireNonNull(pool,          "pool must not be null");
        Objects.requireNonNull(attributes,    "attributes must not be null");

        List<AutoCloseable> handles = new ArrayList<>(3);
        handles.add(meterProvider.registerLongGauge(
                new JfrMeterProvider.MetricDescriptor(METRIC_ACTIVE_THREADS,
                        "Number of threads actively executing fork/join tasks.", "1"),
                attributes,
                pool::getActiveThreadCount));

        handles.add(meterProvider.registerLongGauge(
                new JfrMeterProvider.MetricDescriptor(METRIC_POOL_SIZE,
                        "Current number of threads in the ForkJoin pool.", "1"),
                attributes,
                pool::getPoolSize));

        handles.add(meterProvider.registerLongGauge(
                new JfrMeterProvider.MetricDescriptor(METRIC_TASKS_QUEUED,
                        "Number of queued subtasks in the ForkJoin pool.", "1"),
                attributes,
                pool::getQueuedTaskCount));

        return new ForkJoinPoolMetrics(handles);
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
