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

import io.opentelemetry.api.common.Attributes;

import java.util.function.LongSupplier;

public interface JfrMeterProvider extends AutoCloseable {

    default LongValueRecorder registerLongValueRecorder(String name, String description, String unit) {
        return registerLongValueRecorder(new MetricDescriptor(name, description, unit));
    }

    default DoubleValueRecorder registerDoubleValueRecorder(String name, String description, String unit) {
        return registerDoubleValueRecorder(new MetricDescriptor(name, description, unit));
    }

    LongValueRecorder registerLongValueRecorder(MetricDescriptor metricDescriptor);

    DoubleValueRecorder registerDoubleValueRecorder(MetricDescriptor metricDescriptor);

    /**
     * Registers an observable long gauge that pulls its value from {@code supplier} at each
     * collection interval.  The returned handle should be closed when the gauge is no longer
     * needed (e.g. when the monitored resource shuts down) to deregister the callback.
     */
    default AutoCloseable registerLongGauge(String name, String description, String unit,
                                            Attributes attributes, LongSupplier supplier) {
        return registerLongGauge(new MetricDescriptor(name, description, unit), attributes, supplier);
    }

    AutoCloseable registerLongGauge(MetricDescriptor descriptor, Attributes attributes, LongSupplier supplier);

    @FunctionalInterface
    interface LongValueRecorder {
        void record(long value, Attributes attributes);
    }

    @FunctionalInterface
    interface DoubleValueRecorder {
        void record(double value, Attributes attributes);
    }

    record MetricDescriptor(String name, String description, String unit) {
        public MetricDescriptor {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Metric name must not be blank");
            }
            description = description == null ? "" : description;
            unit = unit == null ? "" : unit;
        }
    }
}
