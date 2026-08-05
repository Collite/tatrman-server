// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.io.File

/**
 * RV-P2.2 DONE — **the gate is the only code path that writes bindings**, asserted by grep rather
 * than by promise.
 *
 * The p2-2 list states the criterion in exactly those terms, and the reason it is worth a test is
 * RV-7: LLM rungs, lookup rounds and the P2.4 re-gate are all *proposers*. The property that keeps
 * them proposers is that none of them can construct a `Binding` — they hand candidates to
 * [org.tatrman.resolver.pipeline.Binder] and take what comes back. A second construction site
 * anywhere would quietly reintroduce the thing RV-7 forbids, and it would look like ordinary code
 * while doing it. Same shape of guard as `NoLlmDependencyTest`, for the same reason: the invariant
 * is structural, so the check should be too.
 *
 * Read as a triangle: the CLASS comes from one place, the DECISION comes from one place, and the
 * lattice `Binding` message is BUILT in one place. Two doors reach the builder and each is
 * documented at its call site — the gate (bindings) and the grounding-trigger annotation
 * (evidence, which cannot bind).
 */
class SingleBinderTest :
    StringSpec({

        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

        fun filesMatching(pattern: Regex): List<String> =
            sources.filter { pattern.containsMatchIn(it.readText()) }.map { it.name }.sorted()

        "the lattice `Binding` message is constructed in exactly one file" {
            // The lookbehind matters: `EntityBinding.newBuilder()` is the DOOR's legacy binding
            // message (`Resolution.bindings`), a different message with a different life, and it
            // is not what this guard is about.
            // `\s*` because ktlint puts the builder chain on its own line.
            filesMatching(Regex("""(?<![A-Za-z])Binding\s*\.\s*newBuilder\(\)""")) shouldContainExactlyInAnyOrder
                listOf("Bindings.kt")
        }

        "only the gate, the re-gate tool and the trigger annotation may reach that constructor" {
            // `ReGate` was added by RV-P2.4 and this assertion is how it announced itself — the
            // guard failed the moment a third file could build a `Binding`, which is the whole
            // point of writing it down rather than trusting review. It is admitted because it is
            // the p2-4 DONE criterion in person: "the only write-path into bindings from outside
            // the core loop is this tool, and it goes through the P2.2 gate". It does — every
            // binding it emits came out of a `Binder.Bind` verdict, never from a hypothesis'
            // say-so. A FOURTH entry here should be argued, not added.
            filesMatching(Regex("""Bindings\.of\(""")) shouldContainExactlyInAnyOrder
                listOf("GroundingTriggers.kt", "LatticeAssembler.kt", "ReGate.kt")
        }

        "the re-gate tool cannot bind on a hypothesis' authority — it only forwards gate verdicts" {
            val reGate = sources.single { it.name == "ReGate.kt" }.readText()
            // Every `Bindings.of` in the tool takes a winner the binder chose. If this ever reads
            // otherwise, a proposer has become a binder (RV-7) and the p2-4 DONE criterion is void.
            Regex("""Bindings\.of\(([^,)]+)""")
                .findAll(reGate)
                .map { it.groupValues[1].trim() }
                .toList() shouldContainExactlyInAnyOrder listOf("verdict.winner")
        }

        "an evidence class is derived in one place, and asked for in two" {
            // `Binder` classifies what it is about to decide on; `GroundingTriggers` classifies
            // what it will never decide on. Nothing else may form an opinion about trust.
            filesMatching(Regex("""EvidenceClasses\.of\(""")) shouldContainExactlyInAnyOrder
                listOf("Binder.kt", "GroundingTriggers.kt")
        }

        "the class ORDER is consulted only by the binder — ranking candidates IS deciding" {
            filesMatching(Regex("""EvidenceClasses\.rank\(""")) shouldContainExactlyInAnyOrder listOf("Binder.kt")
        }
    })
