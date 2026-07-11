package shared.otel

import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.extension.kotlin.asContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Coroutine-aware span helper.
 *
 * Starts a span named [name] (parented to the OTel context that is current at
 * call time), makes it the current context **for the duration of [block]** via
 * the kotlin OTel extension's [asContextElement] — so any span created inside
 * [block] (including ones created by a different component that shares the same
 * [Tracer]/SDK and runs in this coroutine) nests underneath it. This is what
 * gives the in-process `run_query` chain a single, properly-nested trace; across
 * pods the same nesting is delivered by gRPC auto-instrumentation.
 *
 * Records the exception and sets `ERROR` status on a thrown [Throwable], then
 * re-throws. Always ends the span. When the tracer comes from a `noop`
 * OpenTelemetry (telemetry disabled), every operation here is a cheap no-op.
 */
suspend fun <T> Tracer.withSpan(
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
    attributes: Map<String, String> = emptyMap(),
    block: suspend () -> T,
): T {
    val span =
        spanBuilder(name)
            .setSpanKind(kind)
            .startSpan()
    attributes.forEach { (k, v) -> span.setAttribute(k, v) }
    return try {
        withContext(span.asContextElement()) { block() }
    } catch (t: Throwable) {
        span.recordException(t)
        span.setStatus(StatusCode.ERROR, t.message ?: t::class.simpleName.orEmpty())
        throw t
    } finally {
        span.end()
    }
}

/**
 * Span helper for a cold [Flow]: opens [name] (parented to the context current at
 * collection time), runs the **upstream** flow with that span current — so spans
 * created upstream nest underneath it — and re-emits downstream in the collector's
 * own context. Using [flowOn] (not [withContext] around `emit`) is what keeps the
 * Flow context-preservation invariant intact. The span ends when collection
 * completes; an upstream error is recorded with `ERROR` status and re-thrown.
 */
fun <T> Flow<T>.tracedFlow(
    tracer: Tracer,
    name: String,
    kind: SpanKind = SpanKind.INTERNAL,
): Flow<T> =
    flow {
        val span =
            tracer
                .spanBuilder(name)
                .setSpanKind(kind)
                .startSpan()
        try {
            emitAll(this@tracedFlow.flowOn(span.asContextElement()))
        } catch (t: Throwable) {
            span.recordException(t)
            span.setStatus(StatusCode.ERROR, t.message ?: t::class.simpleName.orEmpty())
            throw t
        } finally {
            span.end()
        }
    }
