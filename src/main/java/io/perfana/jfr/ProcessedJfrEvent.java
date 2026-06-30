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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A processed JFR event.
 * The timestamp can be null if not present.
 * Preferably Use one of the static factory methods "of()" to create an instance.
 */
public record ProcessedJfrEvent(@Nullable Instant timestamp,
                                @Nonnull String measurementName,
                                @Nonnull Map<String, String> tags,
                                @Nonnull String field,
                                @Nonnull Number value,
                                @Nonnull Map<String, Object> extraFields,
                                @Nonnull List<String> stacktrace
) {

    public ProcessedJfrEvent {
        if (measurementName.isBlank()) {
            throw new IllegalArgumentException("measurementName cannot be blank.");
        }
        if (field.isBlank()) {
            throw new IllegalArgumentException("field cannot be blank.");
        }
    }

    public static ProcessedJfrEvent of(@Nullable Instant timestamp,
                                       @Nonnull String measurementName,
                                       @Nonnull String field,
                                       @Nonnull Number value) {
        return new ProcessedJfrEvent(timestamp, measurementName, Map.of(), field, value, Map.of(), List.of());
    }

    public static ProcessedJfrEvent of(
            @Nullable Instant timestamp,
            @Nonnull String measurementName,
            @Nonnull String field,
            @Nonnull Number value,
            @Nonnull Map<String, Object> extraFields) {
        return new ProcessedJfrEvent(timestamp, measurementName, Map.of(), field, value, extraFields, List.of());
    }

    public static ProcessedJfrEvent of(
            @Nullable Instant timestamp,
            @Nonnull String measurementName,
            @Nonnull String field,
            @Nonnull Number value,
            @Nonnull Map<String, Object> extraFields,
            @Nonnull List<String> stacktrace) {
        return new ProcessedJfrEvent(timestamp, measurementName, Map.of(), field, value, extraFields, stacktrace);
    }

    public static ProcessedJfrEvent of(
            @Nullable Instant timestamp,
            @Nonnull String measurementName,
            @Nonnull Map<String, String> tags,
            @Nonnull String field,
            @Nonnull Number value) {
        return new ProcessedJfrEvent(timestamp, measurementName, tags, field, value, Map.of(), List.of());
    }

    public static ProcessedJfrEvent of(
            @Nullable Instant timestamp,
            @Nonnull String measurementName,
            @Nonnull Map<String, String> tags,
            @Nonnull String field,
            @Nonnull Number value,
            @Nonnull Map<String, Object> extraFields) {
        return new ProcessedJfrEvent(timestamp, measurementName, tags, field, value, extraFields, List.of());
    }

    public static ProcessedJfrEvent of(
            @Nullable Instant timestamp,
            @Nonnull String measurementName,
            @Nonnull Map<String, String> tags,
            @Nonnull String field,
            @Nonnull Number value,
            @Nonnull Map<String, Object> extraFields,
            @Nonnull List<String> stacktrace) {
        return new ProcessedJfrEvent(timestamp, measurementName, tags, field, value, extraFields, stacktrace);
    }

    public String toStringShort() {
        return "ProcessedJfrEvent{" +
                "timestamp=" + timestamp +
                ", measurementName='" + measurementName + '\'' +
                ", tags=" + tags +
                ", field='" + field + '\'' +
                ", value=" + value +
                ", stacktrace=" + (stacktrace.isEmpty() ? "[]" : stacktrace.get(0) + "...") +
                ", extraFields=" + extraFields +
                '}';
    }
}