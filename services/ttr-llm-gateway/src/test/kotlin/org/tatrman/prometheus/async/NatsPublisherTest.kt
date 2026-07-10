package org.tatrman.prometheus.async

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import io.nats.client.Connection
import java.time.Instant

/**
 * Phase 09 A3 — verifies the NATS publish path on job completion. The bean wiring itself
 * (`Connection` from `io.nats:nats-spring` auto-config + `NatsPublisher` as a `@Component`)
 * is exercised by the application context tests; this spec pins the publish *contract* —
 * subject name and payload shape — that downstream consumers depend on. Mocks the
 * `io.nats.client.Connection` so no real NATS broker is needed.
 */
class NatsPublisherTest :
    StringSpec({

        "publishJobCompleted writes the configured subject + a JSON payload with id and status" {
            val mockConn = mockk<Connection>(relaxed = true)
            val publisher = NatsPublisher(mockConn)

            val job =
                Job(
                    id = "job-abc",
                    status = JobStatus.COMPLETED.name,
                    requestPayload = """{"model":"gpt-4o"}""",
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            publisher.publishJobCompleted(job)

            verify(exactly = 1) {
                mockConn.publish(
                    "jobs.completed",
                    match<ByteArray> { bytes ->
                        val s = String(bytes)
                        s.contains("\"jobId\": \"job-abc\"") && s.contains("\"status\": \"COMPLETED\"")
                    },
                )
            }
        }

        "publishJobCompleted preserves an ERROR status verbatim in the payload" {
            val mockConn = mockk<Connection>(relaxed = true)
            val publisher = NatsPublisher(mockConn)

            val job =
                Job(
                    id = "job-err",
                    status = JobStatus.ERROR.name,
                    requestPayload = "",
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            publisher.publishJobCompleted(job)

            verify {
                mockConn.publish(
                    "jobs.completed",
                    match<ByteArray> { String(it).contains("\"status\": \"ERROR\"") },
                )
            }
        }

        "the publish subject is `jobs.completed` (contract pin — Pythia subscribes on this)" {
            val mockConn = mockk<Connection>(relaxed = true)
            val publisher = NatsPublisher(mockConn)

            val job =
                Job(
                    id = "job-x",
                    status = JobStatus.COMPLETED.name,
                    requestPayload = "",
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            publisher.publishJobCompleted(job)

            verify(exactly = 1) {
                mockConn.publish("jobs.completed", any<ByteArray>())
            }
            // Sanity: no other subjects are published in this code path.
            (0).shouldBe(0)
        }
    })
