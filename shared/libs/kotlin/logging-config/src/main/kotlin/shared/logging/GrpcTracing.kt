// SPDX-License-Identifier: Apache-2.0
package shared.logging

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerCall
import io.grpc.kotlin.CoroutineContextServerInterceptor
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.extension.kotlin.asContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * W3C trace-context propagation across a gRPC hop — the two halves that make
 * "one trace per turn" survive a service boundary.
 *
 * **Why this had to be written rather than configured.** Nothing in the fleet propagated trace
 * context over gRPC. Every service created spans, every service exported them, and each hop opened
 * a *fresh trace*, because the context never reached the wire and would not have been read if it
 * had. The symptom is not an error anywhere — it is a Grafana trace that stops at a service
 * boundary, and a log line that carries a `trace_id` correlating with nothing.
 *
 * The two halves are useless alone, which is the trap to avoid re-opening: injecting on the caller
 * with no extractor on the callee changes nothing observable, and an extractor with no injector
 * finds no header. Land them together, per hop.
 *
 * ⚑ Both depend on the SDK actually carrying propagators. `createOpenTelemetrySdk` sets W3C from
 * 2026-08-14; before that it returned `NoopTextMapPropagator` and both of these would have been
 * silent no-ops.
 *
 * ⚑ Not the `opentelemetry-grpc-1.6` instrumentation library, deliberately: that one also creates
 * its own CLIENT/SERVER spans and renames them by convention, which would restyle every existing
 * span in the fleet as a side effect of fixing propagation. These two do one thing.
 *
 * `Metadata` is case-insensitive over ASCII keys; the propagator hands the carrier lowercase names.
 */

internal val METADATA_SETTER =
    TextMapSetter<Metadata> { carrier, key, value ->
        carrier?.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value)
    }

internal val METADATA_GETTER =
    object : TextMapGetter<Metadata> {
        override fun keys(carrier: Metadata): Iterable<String> = carrier.keys()

        override fun get(
            carrier: Metadata?,
            key: String,
        ): String? = carrier?.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER))
    }

/**
 * Client half: inject the calling coroutine's OTel context into the outgoing `Metadata`.
 *
 * Injects whatever is current at call time. If nothing is — no server span, no enclosing
 * `withSpan` — this writes no header and the callee starts a root, which is the correct degrade
 * rather than a fabricated parent. A caller that wants the hop attached must open a span around
 * the call; `Tracer.withSpan` in `shared.otel` is the coroutine-safe way (`Span.makeCurrent()`
 * around a *suspend* block is not — the context is thread-local and does not survive a
 * dispatch).
 */
class OtelPropagatingClientInterceptor(
    private val openTelemetry: OpenTelemetry,
) : ClientInterceptor {
    override fun <ReqT : Any, RespT : Any> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel,
    ): ClientCall<ReqT, RespT> =
        object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions),
        ) {
            override fun start(
                responseListener: Listener<RespT>,
                headers: Metadata,
            ) {
                openTelemetry.propagators.textMapPropagator.inject(Context.current(), headers, METADATA_SETTER)
                super.start(responseListener, headers)
            }
        }
}

/**
 * Server half: extract the caller's context and make it current **inside the handler coroutine**.
 *
 * This is a [CoroutineContextServerInterceptor] rather than a plain `ServerInterceptor` for a
 * reason worth keeping: a plain one can only touch thread-locals in `interceptCall`, and a
 * grpc-kotlin handler runs in a coroutine that may resume on any thread — so the context would be
 * lost at the first suspension point, usually before anything interesting happened. Contributing
 * `asContextElement()` to the handler's own `CoroutineContext` is what makes `Context.current()`
 * correct for the whole handler, including after suspends.
 *
 * With this in place, two things follow without further work: spans opened in the handler parent
 * to the caller's span, and the Logback `OpenTelemetryAppender` stamps `trace_id`/`span_id` on
 * every log record the handler writes — which is what makes a turn's logs findable *as a turn*.
 *
 * Extraction is rooted at [Context.root] on purpose. A gRPC server thread is pooled and may still
 * carry a context from an unrelated earlier call; rooting there means a request with no
 * `traceparent` yields an empty context (a new root downstream) instead of silently adopting a
 * stranger's trace.
 */
class OtelContextServerInterceptor(
    private val openTelemetry: OpenTelemetry,
) : CoroutineContextServerInterceptor() {
    override fun coroutineContext(
        call: ServerCall<*, *>,
        headers: Metadata,
    ): CoroutineContext {
        val extracted = openTelemetry.propagators.textMapPropagator.extract(Context.root(), headers, METADATA_GETTER)
        return if (extracted == Context.root()) EmptyCoroutineContext else extracted.asContextElement()
    }
}
