// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

/**
 * The declared-vocabulary snapshot the registry is built from. These types mirror
 * the RG-P2 `SnapshotVocabularySource` seam
 * (`services/ttr-fuzzy/.../loader/SnapshotVocabularySource.kt`) field-for-field —
 * this is the **one channel, two consumers** contract (RS-24): fuzzy loads its
 * candidates from it, the resolver builds its registry from it, off the SAME
 * snapshot identity ([hash]). The shared physical home (a snapshot lib) is the
 * RO-13 extraction, still pending — until then each side declares the shape and
 * the conformance is by contract, not a shared import (no service→service coupling).
 */
data class DeclaredValue(
    val id: String,
    val value: String,
)

data class DeclaredVocabularyEntry(
    val category: String,
    val targetRef: String,
    val values: List<DeclaredValue>,
)

data class DeclaredVocabulary(
    val entries: List<DeclaredVocabularyEntry> = emptyList(),
    val locales: List<String> = emptyList(),
)

/**
 * The seam (RS-24). Three implementers over time, all satisfying this interface
 * (rule 6 — name them, don't invent couplings):
 *  - [StubRegistrySource] — the fixture used until the snapshot archive lands;
 *  - [LiveMetadataRegistryAdapter] — the E3-β *step-one* dev-mode reader off Veles
 *    (`meta.v1`), the same interface;
 *  - the real snapshot-archive reader (RO-13), later.
 *
 * [hash] is the snapshot identity: the registry reloads only when it changes.
 */
interface RegistrySource {
    suspend fun fetch(): DeclaredVocabulary

    fun hash(): String
}

/** A fixed fixture source — the stub used until the snapshot archive lands. */
class StubRegistrySource(
    private val vocabulary: DeclaredVocabulary,
    private val snapshotHash: String,
) : RegistrySource {
    override suspend fun fetch(): DeclaredVocabulary = vocabulary

    override fun hash(): String = snapshotHash
}

/**
 * E3-β step one (RS-24): the dev-mode live-metadata adapter. Named per rule 6 so
 * the coupling to Veles/`meta.v1` is explicit rather than invented ad hoc. The
 * body (a `meta.v1` read projected into [DeclaredVocabulary]) lands with the
 * capability-matrix work; until then it yields an empty snapshot with a stable
 * hash, so the pipeline runs against a caller-supplied `Registry` override.
 */
class LiveMetadataRegistryAdapter(
    private val snapshotHash: String = "live-metadata:step-one",
) : RegistrySource {
    override suspend fun fetch(): DeclaredVocabulary = DeclaredVocabulary()

    override fun hash(): String = snapshotHash
}
