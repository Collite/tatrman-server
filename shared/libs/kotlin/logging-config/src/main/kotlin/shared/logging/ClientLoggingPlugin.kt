package shared.logging

import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory

fun HttpClient.installLoggingInterceptor(
    loggerName: String = "ktor.client",
    logRequestPayload: Boolean = true,
    logResponsePayload: Boolean = false,
) {
    val logger = LoggerFactory.getLogger(loggerName)

//    requestPipeline.intercept(PipelinePhase()) { context ->
//        val serviceName = context.url.host
//        val userId = context.headers[HttpHeaders.XUserId] ?: "unknown"
//        val requestId = context.headers[HttpHeaders.XRequestId] ?: "unknown"
//
//        logger.info(
//            "Calling service: {} method={} url={} userId={} requestId={}",
//            serviceName,
//            context.method.value,
//            context.url.buildString(),
//            userId,
//            requestId,
//        )
//
//        if (logRequestPayload) {
//            logger.debug("Request payload to {}: {}", serviceName, context.body.toString().take(1000))
//        }
//
//        proceed(context)
//    }
//
//    responsePipeline.intercept(OnResponse) { context ->
//        val response = context.response
//        val request = response.request
//        val serviceName = request.url.host
//        val userId = request.headers[HttpHeaders.XUserId] ?: "unknown"
//        val requestId = request.headers[HttpHeaders.XRequestId] ?: "unknown"
//        val status = response.status.value
//
//        logger.info(
//            "Response received from {}: status={} userId={} requestId={}",
//            serviceName,
//            status,
//            userId,
//            requestId,
//        )
//
//        if (logResponsePayload) {
//            val bodyText =
//                try {
//                    response.bodyAsText().take(1000)
//                } catch (e: Exception) {
//                    "<unable to read>"
//                }
//            logger.debug("Response payload from {}: {}", serviceName, bodyText)
//        }
//
//        proceed(context)
//    }
}
