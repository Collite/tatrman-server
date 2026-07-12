// SPDX-License-Identifier: Apache-2.0
package shared.logging

class RequestLoggingConfig {
    var loggerName: String = "ktor.request"
    var logRequestPayload: Boolean = true
    var logResponsePayload: Boolean = true
}

// val RequestLogging =
//    ApplicationPlugin<RequestLoggingConfig, Application>(name = "request-logging") {
//        val config = this.config
//        val logger = LoggerFactory.getLogger(config.loggerName)
//
//        environment.monitor.subscribe<io.ktor.server.events.AfterRequest> { request, call ->
//            val spanContext =
//                try {
//                    Span.fromContext(Context.current()).spanContext
//                } catch (e: Exception) {
//                    SpanContext.getInvalid()
//                }
//            val userId = request.header(HttpHeaders.XUserId) ?: "unknown"
//            val requestId = request.header(HttpHeaders.XRequestId) ?: spanContext.traceId().ifEmpty { "unknown" }
//
//            MDC.put(MdcKeys.TRACE_ID, spanContext.traceId())
//            MDC.put(MdcKeys.SPAN_ID, spanContext.spanId())
//            MDC.put(MdcKeys.USER_ID, userId)
//            MDC.put(MdcKeys.REQUEST_ID, requestId)
//
//            logger.info(
//                "Incoming request: method={} uri={} userId={}",
//                request.httpMethod.value,
//                request.uri,
//                userId,
//            )
//        }
//
//        environment.monitor.subscribe<io.ktor.server.events.BeforeResponse> { call, _ ->
//            val userId = MDC.get(MdcKeys.USER_ID) ?: "unknown"
//            logger.info(
//                "Outgoing response: status={} uri={} userId={}",
//                call.response.status()?.value ?: 0,
//                call.request.uri,
//                userId,
//            )
//        }
//
//        environment.monitor.subscribe<io.ktor.server.events.AfterResponse> { _, _ ->
//            MDC.remove(MdcKeys.TRACE_ID)
//            MDC.remove(MdcKeys.SPAN_ID)
//            MDC.remove(MdcKeys.USER_ID)
//            MDC.remove(MdcKeys.REQUEST_ID)
//        }
//    }
