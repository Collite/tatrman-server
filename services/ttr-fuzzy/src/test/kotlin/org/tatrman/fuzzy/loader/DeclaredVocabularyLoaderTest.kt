// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.core.SourceTag

/**
 * RG-P2.S2.T3 — declared vocabulary loads into VOCABULARY-tagged categories
 * keyed by target kind, via the [SnapshotVocabularySource] seam (the RO-13 stub;
 * a live-metadata step-one adapter + the real snapshot reader implement the same
 * interface later).
 */
class DeclaredVocabularyLoaderTest :
    StringSpec({

        // Fixture snapshot: the hero's measure vocabulary + the branch entity term.
        val fixture =
            object : SnapshotVocabularySource {
                override suspend fun fetch(): DeclaredVocabulary =
                    DeclaredVocabulary(
                        listOf(
                            DeclaredVocabularyEntry(
                                category = "md.measure.net",
                                targetRef = "md.measure.net",
                                values =
                                    listOf(
                                        DeclaredValue("trzba", "tržba"),
                                        DeclaredValue("obrat", "obrat"),
                                        DeclaredValue("utrzit", "utržit"),
                                    ),
                            ),
                            DeclaredVocabularyEntry(
                                category = "er.branch",
                                targetRef = "er.branch",
                                values = listOf(DeclaredValue("term-pobocka", "pobočka")),
                            ),
                        ),
                    )

                override fun hash(): String = "fixture-v1"
            }

        "declared vocabulary loads into VOCABULARY categories keyed by target kind" {
            val categories = runBlocking { DeclaredVocabularyLoader.toCategories(fixture.fetch()) }

            categories.keys shouldContainExactlyInAnyOrder listOf("md.measure.net", "er.branch")

            val measures = categories.getValue("md.measure.net")
            measures.map { it.value } shouldContainExactlyInAnyOrder listOf("tržba", "obrat", "utržit")
            measures.forEach {
                it.source shouldBe SourceTag.VOCABULARY
                it.targetRef shouldBe "md.measure.net"
            }

            val branch = categories.getValue("er.branch").single()
            branch.value shouldBe "pobočka"
            branch.id shouldBe "term-pobocka"
            branch.source shouldBe SourceTag.VOCABULARY
            branch.targetRef shouldBe "er.branch"
        }

        "the snapshot hash identifies the declared-vocabulary version (two-clock reload key)" {
            fixture.hash() shouldBe "fixture-v1"
        }
    })
