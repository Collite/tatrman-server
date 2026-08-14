// SPDX-License-Identifier: Apache-2.0
package shared.logging

import io.grpc.Metadata
import io.grpc.ServerCall
import io.mockk.mockk
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The propagation round trip, asserted end to end through the real `Metadata` carrier.
 *
 * The two halves are tested **together** on purpose: each is silently useless without the other,
 * which is the failure this whole change exists to remove. A test of the injector alone would pass
 * against a fleet that never reads the header.
 */
class GrpcTracingTest :
    StringSpec({

        fun fakeCall(): ServerCall<*, *> = mockk<ServerCall<Any, Any>>(relaxed = true)

        fun sdk(): OpenTelemetrySdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(SdkTracerProvider.builder().build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build()

        "a span's context survives the metadata round trip with its trace id intact" {
            val otel = sdk()
            val span = otel.getTracer("test").spanBuilder("caller").startSpan()
            val headers = Metadata()

            try {
                span.makeCurrent().use {
                    otel.propagators.textMapPropagator.inject(Context.current(), headers, METADATA_SETTER)
                }

                // The wire form is what a non-Kotlin callee would read, so assert on it.
                val raw = headers.get(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER))
                raw.shouldNotBeNull()
                raw shouldContain span.spanContext.traceId

                val extracted =
                    otel.propagators.textMapPropagator.extract(Context.root(), headers, METADATA_GETTER)
                val remote = Span.fromContext(extracted).spanContext
                remote.traceId shouldBe span.spanContext.traceId
                remote.spanId shouldBe span.spanContext.spanId
                remote.isRemote shouldBe true
            } finally {
                span.end()
            }
        }

        "no current context ⇒ no header, and the callee starts a root rather than a fabricated child" {
            val otel = sdk()
            val headers = Metadata()

            otel.propagators.textMapPropagator.inject(Context.root(), headers, METADATA_SETTER)
            headers.get(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER)) shouldBe null

            val extracted = otel.propagators.textMapPropagator.extract(Context.root(), headers, METADATA_GETTER)
            extracted shouldBe Context.root()
            Span.fromContext(extracted).spanContext.isValid shouldBe false
        }

        "the server interceptor contributes NOTHING when there is no traceparent" {
            // ⚑ The distinction that matters: an empty coroutine context lets the handler open its
            // own root. Contributing `Context.root().asContextElement()` instead would PIN the
            // handler to an empty context, and a pooled gRPC thread carrying a stale context from
            // an unrelated call is exactly the case that makes the difference visible.
            val interceptor = OtelContextServerInterceptor(sdk())
            interceptor.coroutineContext(fakeCall(), Metadata()) shouldBe EmptyCoroutineContext
        }

        "the server interceptor contributes the extracted context when there IS one" {
            val otel = sdk()
            val span = otel.getTracer("test").spanBuilder("caller").startSpan()
            val headers = Metadata()
            try {
                span.makeCurrent().use {
                    otel.propagators.textMapPropagator.inject(Context.current(), headers, METADATA_SETTER)
                }
                OtelContextServerInterceptor(otel).coroutineContext(fakeCall(), headers) shouldNotBe
                    EmptyCoroutineContext
            } finally {
                span.end()
            }
        }

        "a noop OpenTelemetry injects nothing — telemetry off must stay free" {
            val headers = Metadata()
            val span =
                OpenTelemetry
                    .noop()
                    .getTracer("test")
                    .spanBuilder("caller")
                    .startSpan()
            span.makeCurrent().use {
                OpenTelemetry.noop().propagators.textMapPropagator.inject(
                    Context.current(),
                    headers,
                    METADATA_SETTER,
                )
            }
            span.end()
            headers.keys().isEmpty() shouldBe true
        }
    })
