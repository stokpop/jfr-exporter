# Change log jfr-exporter

### Unreleased
* Added OTLP/HTTP metrics export support for collectors on port 4318 using a base collector endpoint
* Added `otlpProtocol` and `otlpSignal` options so OTLP/gRPC on port 4317 remains supported and HTTP signal paths are added automatically
* Added `otlpFormat=json` support for OTLP/HTTP metrics export
* Added optional `enableExecutorMBeans` support to expose discovered executor pools through JMX
* Added true executor MBeans-only mode when `enableExecutorMBeans` is used without OTLP or Influx configuration

### v0.5.0: July 2024
* Deprecated the application argument, use service tag instead
* Specify multiple tags using tag=name/value, e.g. tag=service/afterburner-fe
* Dashboard improvements, uses tags: service,systemUnderTest,testEnvironment
* Improved default tags code efficiency

### v0.4.1: May 2024
* socket events: lowered dynamic ports to 16_000 

### v0.4.0: May 2024
* Added new events:
    * Native Memory
    * Container CPU and memory
    * Thread context switch rate
* Updated dashboard with new events
* Improved dashboard, application selection is working for all panels

### v0.3.0: January 2024
* Added new events: Java Monitor waits and enters, Network read/write
* New dashboard with new events

### v0.3.1: April 2024
* Make use of buffer to send events in batches
* Make event handling more robust by catching exceptions
* Added disableStackTraces option to limit the amount of stacktrace data

