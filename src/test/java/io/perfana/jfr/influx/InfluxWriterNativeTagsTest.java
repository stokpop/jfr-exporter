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
package io.perfana.jfr.influx;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InfluxWriterNativeTagsTest {

    @Test
    void globalAndEventTagsAreSortedTogether() {
        Map<String, String> globalTags = Map.of("service", "afterburner", "systemUnderTest", "sut");
        Map<String, String> eventTags = Map.of("size-bucket", "1MB-10MB");

        String tags = InfluxWriterNative.prepareTagsForInflux(globalTags, eventTags);

        assertEquals(",service=afterburner,size-bucket=1MB-10MB,systemUnderTest=sut", tags);
    }

    @Test
    void noEventTagsGivesGlobalTagsOnly() {
        String tags = InfluxWriterNative.prepareTagsForInflux(Map.of("service", "afterburner"), Map.of());

        assertEquals(",service=afterburner", tags);
    }

    @Test
    void noTagsGivesEmptyString() {
        assertEquals("", InfluxWriterNative.prepareTagsForInflux(Map.of(), Map.of()));
    }
}
