// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.grpc.Server
import io.grpc.ServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.tatrman.nlp.v1.NlpServiceGrpcKt
import org.tatrman.nlp.v1.StatusRequest
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.GrpcNlpClient
import java.util.concurrent.TimeUnit

/**
 * TG-P0-F3 — the resolver's upstream calls are logged.
 *
 * **Why this is a behavioural test and not a "does the source contain X" one.** The interceptor is
 * added inside a private channel builder, so nothing about it is observable through the client's
 * API; the only honest assertion is that a real call against a real server produces the log line.
 * Deleting the `.intercept(...)` in `Clients.kt` makes this test fail, which is the whole point —
 * the line is one character away from being dropped by a merge and it is the line that separates
 * "lex-matcher returned nothing" from "the core discarded what it returned".
 *
 * ⚑ The level is set here rather than assumed: the resolver's `logback.xml` pins `grpc.out` to
 * `${LOG_LEVEL:-INFO}`, so in a test JVM it is INFO and the interceptor's `isDebugEnabled` guard
 * would skip the payload silently.
 */
class OutgoingCallLoggingTest :
    StringSpec({

        "a call through GrpcNlpClient logs the outgoing payload and its response at DEBUG" {
            val fake =
                object : NlpServiceGrpcKt.NlpServiceCoroutineImplBase() {
                    override suspend fun getStatus(request: StatusRequest): StatusResponse =
                        StatusResponse.newBuilder().setReady(true).build()
                }

            val server: Server =
                ServerBuilder
                    .forPort(0)
                    .addService(fake)
                    .build()
                    .start()

            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val outLogger = ctx.getLogger("grpc.out")
            val previousLevel = outLogger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }

            // The interceptor logs to `grpc.out.<fullMethodName>`; additivity carries the event up
            // to this appender on the parent.
            outLogger.level = Level.DEBUG
            outLogger.addAppender(appender)

            try {
                GrpcNlpClient("localhost", server.port).use { client ->
                    runBlocking { client.getStatus() }.ready shouldBe true
                }

                val messages = appender.list.map { it.formattedMessage }
                messages shouldHaveAtLeastSize 2

                val method = "org.tatrman.nlp.v1.NlpService/GetStatus"
                messages.first { it.startsWith("→") && it.contains("payload=") } shouldContain method
                messages.first { it.startsWith("←") } shouldContain "ready: true"
            } finally {
                outLogger.detachAppender(appender)
                outLogger.level = previousLevel
                appender.stop()
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    })
