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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class JfrExporterTest {

    @Test
    void testStartup() {
        JfrExporter jfrExporter = new JfrExporter();
        String[] args = {"-d", "--duration", "PT2S"};
        Arguments arguments = Arguments.parseArgs(args);
        jfrExporter.start(arguments);
    }

    @Test
    void splitAgentArgs() {
        String[] strings = JfrExporter.splitAgentArgs("--duration=PT2S,--tag=service/MyApp,tag=systemUnderTest/afterburner");
        assertEquals(6, strings.length);
    }

    @Test
    void splitAgentArgsWithOtlpBaseEndpointAndSignal() {
        String[] strings = JfrExporter.splitAgentArgs("duration=PT30s,otlpEndpoint=http://localhost:4318,otlpSignal=metrics");

        assertEquals(6, strings.length);
        assertEquals("duration", strings[0]);
        assertEquals("PT30s", strings[1]);
        assertEquals("otlpEndpoint", strings[2]);
        assertEquals("http://localhost:4318", strings[3]);
        assertEquals("otlpSignal", strings[4]);
        assertEquals("metrics", strings[5]);
    }

    @Test
    void splitAgentArgsWithExecutorMBeansFlag() {
        String[] strings = JfrExporter.splitAgentArgs("duration=PT30s,enableExecutorMBeans,otlpEndpoint=http://localhost:4318");

        assertEquals(5, strings.length);
        assertEquals("duration", strings[0]);
        assertEquals("PT30s", strings[1]);
        assertEquals("enableExecutorMBeans", strings[2]);
        assertEquals("otlpEndpoint", strings[3]);
        assertEquals("http://localhost:4318", strings[4]);
    }

    @Test
    void executorMBeansOnlyModeDoesNotAttachToProcessId() {
        JfrExporter jfrExporter = new JfrExporter();
        Arguments arguments = Arguments.parseArgs(new String[]{"--processId", "999999", "--duration", "PT0.1S", "--enableExecutorMBeans"});

        assertDoesNotThrow(() -> jfrExporter.start(arguments));
    }

}
