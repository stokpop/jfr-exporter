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
package io.perfana.jfr.event;

import io.perfana.jfr.NoopEventProcessor;
import io.perfana.jfr.ProcessedJfrEvent;
import jdk.jfr.EventType;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedStackTrace;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VirtualThreadEventTest {

    private RecordedEvent mockEvent(String eventTypeName) {
        RecordedEvent event = Mockito.mock(RecordedEvent.class);
        EventType eventType = Mockito.mock(EventType.class);
        when(eventType.getName()).thenReturn(eventTypeName);
        when(event.getEventType()).thenReturn(eventType);
        when(event.getStartTime()).thenReturn(Instant.now());
        return event;
    }

    @Test
    void activeCountIncrementsOnStart() {
        var captured = new java.util.ArrayList<ProcessedJfrEvent>();
        var processor = (io.perfana.jfr.JfrEventProcessor) captured::add;

        VirtualThreadEvent handler = new VirtualThreadEvent(processor);

        RecordedEvent start = mockEvent(VirtualThreadEvent.JDK_VIRTUAL_THREAD_START);
        handler.onEvent(start);

        assertEquals(1, captured.size());
        assertEquals("virtual-threads", captured.get(0).measurementName());
        assertEquals(1L, captured.get(0).value());
    }

    @Test
    void activeCountDecrementsOnEnd() {
        var captured = new java.util.ArrayList<ProcessedJfrEvent>();
        var processor = (io.perfana.jfr.JfrEventProcessor) captured::add;

        VirtualThreadEvent handler = new VirtualThreadEvent(processor);

        handler.onEvent(mockEvent(VirtualThreadEvent.JDK_VIRTUAL_THREAD_START));
        handler.onEvent(mockEvent(VirtualThreadEvent.JDK_VIRTUAL_THREAD_START));
        handler.onEvent(mockEvent(VirtualThreadEvent.JDK_VIRTUAL_THREAD_END));

        ProcessedJfrEvent lastEvent = captured.get(captured.size() - 1);
        assertEquals("virtual-threads", lastEvent.measurementName());
        assertEquals(1L, lastEvent.value());
    }

    @Test
    void pinnedEventBelowThresholdNotReported() {
        var captured = new java.util.ArrayList<ProcessedJfrEvent>();
        var processor = (io.perfana.jfr.JfrEventProcessor) captured::add;

        VirtualThreadEvent handler = new VirtualThreadEvent(processor);

        RecordedEvent pinned = mockEvent(VirtualThreadEvent.JDK_VIRTUAL_THREAD_PINNED);
        when(pinned.getLong("duration")).thenReturn(1_000_000L); // 1 ms, below 20 ms threshold

        handler.onEvent(pinned);

        assertTrue(captured.isEmpty(), "Pinned event below threshold should not be reported");
    }

    @Test
    void pinnedEventAboveThresholdIsReported() {
        var captured = new java.util.ArrayList<ProcessedJfrEvent>();
        var processor = (io.perfana.jfr.JfrEventProcessor) captured::add;

        VirtualThreadEvent handler = new VirtualThreadEvent(processor);

        RecordedEvent pinned = mockEvent(VirtualThreadEvent.JDK_VIRTUAL_THREAD_PINNED);
        when(pinned.getLong("duration")).thenReturn(50_000_000L); // 50 ms, above 20 ms threshold
        when(pinned.getStackTrace()).thenReturn(null);

        handler.onEvent(pinned);

        assertEquals(1, captured.size());
        assertEquals("virtual-thread-pinned", captured.get(0).measurementName());
        assertEquals(50_000_000L, captured.get(0).value());
    }

    @Test
    void submitFailedIsReported() {
        var captured = new java.util.ArrayList<ProcessedJfrEvent>();
        var processor = (io.perfana.jfr.JfrEventProcessor) captured::add;

        VirtualThreadEvent handler = new VirtualThreadEvent(processor);

        RecordedEvent failed = mockEvent(VirtualThreadEvent.JDK_VIRTUAL_THREAD_SUBMIT_FAILED);
        when(failed.getLong("javaThreadId")).thenReturn(42L);
        when(failed.getString("exceptionMessage")).thenReturn("scheduler queue full");

        handler.onEvent(failed);

        assertEquals(1, captured.size());
        ProcessedJfrEvent event = captured.get(0);
        assertEquals("virtual-thread-submit-failed", event.measurementName());
        assertEquals(1, event.value());
        assertEquals("scheduler queue full", event.extraFields().get("exceptionMessage"));
    }

    @Test
    void getEventSettingsReturnsAllFourEvents() {
        VirtualThreadEvent handler = new VirtualThreadEvent(new NoopEventProcessor());
        var settings = handler.getEventSettings();
        // On JDK < 21 the list is empty; on JDK 21+ it contains all four events
        if (Runtime.version().feature() < 21) {
            assertTrue(settings.isEmpty(), "Expected empty settings on JDK < 21");
        } else {
            assertEquals(4, settings.size());
            var names = settings.stream().map(s -> s.getName()).toList();
            assertTrue(names.contains(VirtualThreadEvent.JDK_VIRTUAL_THREAD_START));
            assertTrue(names.contains(VirtualThreadEvent.JDK_VIRTUAL_THREAD_END));
            assertTrue(names.contains(VirtualThreadEvent.JDK_VIRTUAL_THREAD_PINNED));
            assertTrue(names.contains(VirtualThreadEvent.JDK_VIRTUAL_THREAD_SUBMIT_FAILED));
        }
    }
}
