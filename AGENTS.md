# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw clean package

# Build with version
./mvnw -Drevision=0.6.0 clean package

# Run tests
./mvnw test

# Run single test class
./mvnw test -Dtest=JfrEventHandlerTest

# Check/fix license headers
./mvnw license:format
```

The license plugin runs automatically at `process-sources` phase — all `.java` files must have the Apache 2 header from `src/etc/header.txt`. Adding a new `.java` file without the header causes build failure; run `./mvnw license:format` to fix it.

Build requires JDK 17+ and Maven 3.6.3+.

## Architecture

**JfrExporter** is a JVM agent (and standalone app) that streams JFR events to InfluxDB v1 (line protocol).

### Two entry modes

- **Agent**: `premain` / `agentmain` in `JfrExporter` — runs on a daemon thread inside the monitored JVM. No need to add `-XX:StartFlightRecording`. Uses `RecordingStream` (internal JVM).
- **Standalone**: `main` in `JfrExporter` — attaches to an external process via `VirtualMachine.attach()` + `EventStream.openRepository()`.

### Event pipeline

```
JFR runtime
  → JfrConnector (RecordingStream or EventStream)
    → JfrEventHandler (routes by event name)
      → *Event classes (parse RecordedEvent → ProcessedJfrEvent)
        → JfrEventProcessor (InfluxEventProcessor or NoopEventProcessor)
          → InfluxWriterNative (batch buffer → InfluxDB v1 HTTP write)
```

### Key abstractions

| Interface/class | Role |
|---|---|
| `JfrEventProvider` | Each event class implements this; returns `List<JfrEventSettings>` declaring which JFR event names to subscribe and their period/threshold config |
| `OnJfrEvent` | Single `onEvent(RecordedEvent)` callback; implemented by each event class |
| `JfrEventSettings` | Bundles a JFR event name + `OnJfrEvent` handler + optional period/threshold |
| `ProcessedJfrEvent` | Immutable record: measurement name, tags, field, numeric value, optional stacktrace + extra fields |
| `JfrEventProcessor` | Receives `ProcessedJfrEvent`; `InfluxEventProcessor` writes to Influx, `NoopEventProcessor` discards |
| `InfluxWriter` | Converts `ProcessedJfrEvent` to InfluxDB line protocol and POSTs in batches (≤1000 points or 5s) |

### Adding a new JFR event

1. Create a class in `io.perfana.jfr.event` that implements both `JfrEventProvider` and `OnJfrEvent`.
2. In `getEventSettings()`, return `JfrEventSettings.of(eventName, this)` with optional `.withPeriod()` / `.withThreshold()`.
3. In `onEvent()`, parse the `RecordedEvent` and call `eventProcessor.processEvent(ProcessedJfrEvent.of(...))`.
4. Register in `JfrExporter.start()`: instantiate the class, call `getEventSettings().forEach(eventHandler::register)`.

### InfluxDB v1 line protocol

`InfluxWriterNative` writes to `<influxUrl>/write`. Tags are sorted alphabetically before writing (InfluxDB performance requirement). String field values are double-quoted and escaped. Numeric values are unquoted. Default database: `jfr`. Default retention policy: `autogen`.

Tags flow from two places: global tags from `Arguments` (set once at startup via `InfluxWriterConfig`) and per-event tags from `ProcessedJfrEvent.tags()`.

### Logging

Custom `Logger` class (no external logging framework). Enable with `-Dio.perfana.jfr.debug=true` or `--debug` arg. Trace level: `-Dio.perfana.jfr.trace=true`.

### Dashboards

Grafana dashboards in `dashboards/` — versioned JSON exports (`0.3`–`0.6`). Dashboard `0.5+` expects tags `service`, `systemUnderTest`, `testEnvironment`. Virtual thread panels added in `0.6`.
