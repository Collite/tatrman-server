package org.tatrman.kantheon.charon.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Charon P3 Stage 3.1 T6 — the legality matrix's contracts §2 confirmation.
 *
 * [legality] in `Legality.kt` is the single source of truth; the contracts §2
 * table is hand-derived from it. This spec **generates** the per-RPC verb each
 * `(source → target)` cell carries and asserts it against the documented §2
 * matrix — so a divergence between the code and the doc fails the build (the
 * matrix Pythia's data plane trusts). [MovePlannerSpec] separately exercises
 * every cell through the planner; this one pins the doc table.
 */
class LegalityMatrixDocSpec :
    StringSpec({

        // The contracts §2 table: source ↓ / target → ⇒ the move verb(s).
        // "—" marks a DISALLOWED cell; "noop" marks SAME_LOCATION.
        val expected =
            mapOf(
                // seaweed source
                (LocationKind.SEAWEED to LocationKind.SEAWEED) to "noop",
                (LocationKind.SEAWEED to LocationKind.REDIS) to "Materialize/Copy",
                (LocationKind.SEAWEED to LocationKind.WORKER_DF) to "Stage",
                (LocationKind.SEAWEED to LocationKind.DB_TABLE) to "Materialize/Copy",
                // redis source
                (LocationKind.REDIS to LocationKind.SEAWEED) to "Materialize/Copy",
                (LocationKind.REDIS to LocationKind.REDIS) to "noop",
                (LocationKind.REDIS to LocationKind.WORKER_DF) to "Stage",
                (LocationKind.REDIS to LocationKind.DB_TABLE) to "Materialize/Copy",
                // worker_df source
                (LocationKind.WORKER_DF to LocationKind.SEAWEED) to "Materialize/Copy",
                (LocationKind.WORKER_DF to LocationKind.REDIS) to "Materialize/Copy",
                (LocationKind.WORKER_DF to LocationKind.WORKER_DF) to "Stage",
                (LocationKind.WORKER_DF to LocationKind.DB_TABLE) to "Materialize/Copy",
                // db_table source
                (LocationKind.DB_TABLE to LocationKind.SEAWEED) to "Materialize/Copy",
                (LocationKind.DB_TABLE to LocationKind.REDIS) to "Materialize/Copy",
                (LocationKind.DB_TABLE to LocationKind.WORKER_DF) to "Stage",
                (LocationKind.DB_TABLE to LocationKind.DB_TABLE) to "Copy",
            )

        /** Derive the documented verb for a cell from the [legality] matrix. */
        fun verbFor(
            source: LocationKind,
            target: LocationKind,
        ): String {
            // worker_df target ⇒ Stage only (Materialize/Copy never target a worker).
            if (target == LocationKind.WORKER_DF) {
                return if (legalityOf(MoveRpc.STAGE, source, target) == Legality.ALLOWED ||
                    legalityOf(MoveRpc.STAGE, source, target) == Legality.SAME_LOCATION
                ) {
                    "Stage"
                } else {
                    "—"
                }
            }
            val mat = legalityOf(MoveRpc.MATERIALIZE, source, target)
            val cpy = legalityOf(MoveRpc.COPY, source, target)
            return when {
                cpy == Legality.SAME_LOCATION || mat == Legality.SAME_LOCATION -> "noop"
                mat == Legality.ALLOWED && cpy == Legality.ALLOWED -> "Materialize/Copy"
                cpy == Legality.ALLOWED -> "Copy"
                mat == Legality.ALLOWED -> "Materialize"
                else -> "—"
            }
        }

        expected.forEach { (cell, verb) ->
            val (source, target) = cell
            "matrix cell $source → $target is '$verb' (contracts §2)" {
                verbFor(source, target) shouldBe verb
            }
        }

        "Materialize never targets a worker (Stage is the primary way in; Copy is the explicit cross-engine intent)" {
            // No Materialize cell into a worker is ALLOWED (the diagonal
            // worker→worker is SAME_LOCATION, also not a real Materialize).
            LocationKind.entries.forEach { src ->
                (legalityOf(MoveRpc.MATERIALIZE, src, LocationKind.WORKER_DF) == Legality.ALLOWED) shouldBe false
            }
        }
    })
