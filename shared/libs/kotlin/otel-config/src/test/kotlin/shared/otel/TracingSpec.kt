package shared.otel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.test.runTest

class TracingSpec :
    StringSpec({

        fun sdkWith(exporter: InMemorySpanExporter): OpenTelemetrySdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(
                    SdkTracerProvider
                        .builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build(),
                ).build()

        "withSpan nests an inner span under the outer one in a single trace" {
            val exporter = InMemorySpanExporter.create()
            val tracer = sdkWith(exporter).getTracer("test")

            runTest {
                tracer.withSpan("outer") {
                    tracer.withSpan("inner") { }
                }
            }

            val spans = exporter.finishedSpanItems.associateBy { it.name }
            val outer = spans.getValue("outer")
            val inner = spans.getValue("inner")
            // Same trace.
            inner.traceId shouldBe outer.traceId
            // inner's parent is outer.
            inner.parentSpanId shouldBe outer.spanId
            // outer is a root.
            outer.parentSpanContext.isValid shouldBe false
        }

        "withSpan records the exception and sets ERROR status, then rethrows" {
            val exporter = InMemorySpanExporter.create()
            val tracer = sdkWith(exporter).getTracer("test")

            runTest {
                shouldThrow<IllegalStateException> {
                    tracer.withSpan("boom") { error("kaboom") }
                }
            }

            val span = exporter.finishedSpanItems.single { it.name == "boom" }
            span.status.statusCode shouldBe StatusCode.ERROR
            span.events.any { it.name == "exception" } shouldBe true
        }
    })
