// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.tatrman.chrono.grpc.ChronoGroundingService
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import java.io.File

/**
 * RG-P3.S1.T4 — the D-T1 invariant, conformance-assertable two ways.
 *
 * (1) **Behavioural:** a relative span (`včera` / `minulý měsíc` — the „poslední fiskální čtvrtletí"
 * class) resolved at two different injected `reference_datetime` values yields intervals that differ
 * accordingly. If any grounding code read a wall clock instead of the request's reference, the two
 * results would be identical (both anchored to "now") — so divergence *is* the proof.
 *
 * (2) **Architectural:** no grounding service or the kernel calls a wall clock
 * (`Instant.now()`/`LocalDate.now()`/`Clock.systemDefaultZone()`/`System.currentTimeMillis()` …)
 * anywhere in main source. `System.nanoTime()` is deliberately allowed — it is a *monotonic duration
 * timer* used only for latency metrics, never a wall-clock read.
 */
class NoClockReadsTest :
    StringSpec({

        val tz = "Europe/Prague"

        fun request(
            span: String,
            ref: String,
        ): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setKind(EntityKind.DATE_TIME)
                .setPackage("cnc")
                .setContext(GroundingContext.newBuilder().setReferenceDatetime(ref).setTimezone(tz))
                .build()

        fun groundInterval(
            span: String,
            ref: String,
        ): String {
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)
            val r = runBlocking { svc.ground(request(span, ref)) }
            r.status shouldBe GroundResponse.Status.OK
            return r.result.normalized.interval.start
        }

        "'včera' resolves relative to the injected reference_datetime, not a wall clock" {
            // reference-1 (2026-05-15) ⇒ yesterday = 2026-05-14; reference-2 (2024-11-20) ⇒ 2024-11-19.
            val a = groundInterval("včera", "2026-05-15T12:00:00+02:00")
            val b = groundInterval("včera", "2024-11-20T12:00:00+01:00")
            a shouldStartWith "2026-05-14"
            b shouldStartWith "2024-11-19"
            (a == b) shouldBe false
        }

        "'minulý měsíc' shifts with the reference year+month" {
            val a = groundInterval("minulý měsíc", "2026-05-15T12:00:00+02:00")
            val b = groundInterval("minulý měsíc", "2024-11-20T12:00:00+01:00")
            a shouldStartWith "2026-04-01"
            b shouldStartWith "2024-10-01"
        }

        "no grounding service or the kernel reads a wall clock in main source (nanoTime allowed)" {
            val root = repoRoot()
            val mainTrees =
                listOf(
                    "services/chrono/src/main",
                    "services/money/src/main",
                    "services/geo/src/main",
                    "services/ttr-grounding-mcp/src/main",
                    "shared/libs/kotlin/ttr-grounding-core/src/main",
                ).map { File(root, it) }

            // Wall-clock reads — forbidden. System.nanoTime()/nanoTime is a monotonic timer, NOT here.
            val wallClock =
                Regex(
                    """\b(Instant|LocalDate|LocalDateTime|LocalTime|OffsetDateTime|ZonedDateTime|""" +
                        """Year|YearMonth|MonthDay|OffsetTime)\.now\s*\(""" +
                        """|\bClock\.(systemDefaultZone|systemUTC|system)\s*\(""" +
                        """|\bSystem\.currentTimeMillis\s*\(""",
                )

            val offenders =
                mainTrees
                    .flatMap { tree -> tree.walkTopDown().filter { it.isFile && it.extension == "kt" } }
                    .mapNotNull { f ->
                        val code = stripComments(f.readText())
                        if (wallClock.containsMatchIn(code)) f.path else null
                    }

            offenders.shouldBeEmpty()
        }
    })

/** Walk up from the test working directory until the repo root (the dir holding settings.gradle.kts). */
private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
        if (File(dir, "settings.gradle.kts").exists()) return dir
        dir = dir.parentFile
    }
    error("could not locate repo root (settings.gradle.kts) from ${System.getProperty("user.dir")}")
}

/** Remove `//` line comments and `/* … */` block comments so a KDoc mention isn't a false positive. */
private fun stripComments(src: String): String =
    src
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
