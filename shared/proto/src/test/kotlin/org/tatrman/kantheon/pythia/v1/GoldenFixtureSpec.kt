package org.tatrman.kantheon.pythia.v1

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Stage 1.1 T5 — the three worked-example golden artifacts (design §4.1/4.2/4.3)
 * must parse into `InvestigationArtifact` with zero unknown-field warnings. These
 * are the reference inputs the renderer/synth specs (Phase 2 Stage 2.4) and the
 * e2e fixtures (2.4 / 3.3) assert against — keep field names exactly as generated.
 */
class GoldenFixtureSpec :
    StringSpec({

        val goldens = listOf("nescafe-maggi", "rca-channel", "forecast")

        fun load(name: String): InvestigationArtifact {
            val json =
                requireNotNull(
                    object {}.javaClass.getResourceAsStream("/golden/$name-artifact.json"),
                ) { "missing golden fixture $name" }.bufferedReader().readText()
            // Strict parse: do NOT ignore unknown fields — a typo in a field name must fail here.
            val builder = InvestigationArtifact.newBuilder()
            JsonFormat.parser().merge(json, builder)
            return builder.build()
        }

        goldens.forEach { name ->
            "golden $name parses into InvestigationArtifact with no unknown fields" {
                val artifact = load(name)
                artifact.id.isNotBlank() shouldBe true
                artifact.status shouldBe Status.STATUS_DONE
                artifact.plan.nodesCount shouldBeGreaterThan 0
                artifact.hasConclusion() shouldBe true
            }
        }

        "nescafe-maggi golden is the procedural reference (trivial hidden hypothesis)" {
            val a = load("nescafe-maggi")
            a.resolution.resolvedIntent.kind shouldBe IntentKind.INTENT_PROCEDURAL
            a.hypothesesList shouldHaveSize 1
            a.hypothesesList.first().displayPriority shouldBe DisplayPriority.DISPLAY_HIDDEN
            a.conclusion.stopReason shouldBe StopReason.STOP_GOAL_REACHED
        }

        "rca-channel golden carries a revised plan, loose ends, and a heuristic confidence" {
            val a = load("rca-channel")
            a.resolution.resolvedIntent.kind shouldBe IntentKind.INTENT_RCA
            a.plan.revision shouldBe 2
            a.looseEndsList shouldHaveSize 2
            a.conclusion.confidence.kind shouldBe ConfidenceKind.CONFIDENCE_HEURISTIC
            a.conclusion.stopReason shouldBe StopReason.STOP_HARD_CAP
        }

        "forecast golden carries model nodes and a model-based confidence" {
            val a = load("forecast")
            a.resolution.resolvedIntent.kind shouldBe IntentKind.INTENT_FORECAST
            a.plan.nodesList.any { it.kindCase == PlanNode.KindCase.MODEL } shouldBe true
            a.conclusion.confidence.kind shouldBe ConfidenceKind.CONFIDENCE_MODEL_BASED
        }
    })
