// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.lexicon

import org.slf4j.LoggerFactory
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.LexiconArchive
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.snapshot.SnapshotId
import org.tatrman.ttr.snapshot.SnapshotReadResult
import org.tatrman.ttr.snapshot.SnapshotReader
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * RV-P1.6 T4 (RV-42) — reads one kernel's `ground:` slice out of the RV-P1.2 compiled lexicon
 * archive.
 *
 * The same seam and the same posture as lex-matcher's `LexiconArchiveSource` (RV-P1.4 T3), for the
 * same reasons: reading costs `ttr-snapshot` + `ttr-lexicon` and no compiler, and a **missing or
 * unreadable archive is never fatal**. A chrono with no lexicon is the pre-RV chrono — its own
 * generative rules, no estate vocabulary — and refusing to start because an optional layer is
 * absent would take grounding down for every estate that has not authored one yet.
 *
 * [refresh] is the S-3 hook: idempotent, cheap when the bytes have not moved (the archive id is
 * compared before anything is parsed), and safe to call from a request thread or an operator
 * endpoint. [current] never touches the disk — it reports what the last refresh loaded, so the
 * hot path of every ground call is a field read.
 */
class GroundingSliceSource(
    private val kind: String,
    private val archivePath: Path?,
) {
    private val logger = LoggerFactory.getLogger(GroundingSliceSource::class.java)
    private val slice = AtomicReference(GroundingSlice.empty(kind))
    private val loadedArchiveId = AtomicReference("")

    /** What is serving right now. Empty until the first [refresh], which is honest. */
    fun current(): GroundingSlice = slice.get()

    /**
     * Re-read the archive if its bytes moved. Returns the slice now serving.
     *
     * Every failure path keeps the previously loaded slice and logs once: a broken artifact should
     * be loud in the log and invisible to a query, never the reverse.
     */
    fun refresh(): GroundingSlice {
        val path = archivePath
        if (path == null) return slice.get()

        if (!path.exists()) {
            if (loadedArchiveId.get().isNotEmpty()) {
                logger.warn("Compiled lexicon disappeared from {} — keeping the loaded {} slice", path, kind)
            }
            return slice.get()
        }

        val bytes =
            try {
                path.readBytes()
            } catch (e: Exception) {
                logger.warn("Compiled lexicon at {} is unreadable: {}", path, e.message)
                return slice.get()
            }

        val id = SnapshotId.of(bytes)
        if (id == loadedArchiveId.get()) return slice.get()

        val contents =
            when (val read = SnapshotReader.read(bytes)) {
                is SnapshotReadResult.Ok -> read.contents
                is SnapshotReadResult.Failure -> {
                    logger.warn("Compiled lexicon at {} is not a readable archive: {}", path, read.reason)
                    return slice.get()
                }
            }

        if (contents.manifest.kind != LexiconArchive.KIND) {
            logger.warn(
                "Archive at {} is kind='{}', expected '{}' — not a lexicon archive",
                path,
                contents.manifest.kind,
                LexiconArchive.KIND,
            )
            return slice.get()
        }

        val json =
            contents.docs[LexiconArchive.LEXICON] ?: run {
                logger.warn("Lexicon archive at {} has no {}", path, LexiconArchive.LEXICON)
                return slice.get()
            }

        val lexicon =
            try {
                CompiledLexicon.fromJson(json)
            } catch (e: Exception) {
                logger.warn("Lexicon archive at {} has an undecodable {}: {}", path, LexiconArchive.LEXICON, e.message)
                return slice.get()
            }

        val loaded = sliceOf(lexicon)
        slice.set(loaded)
        loadedArchiveId.set(id)
        logger.info(
            "Grounding slice '{}' loaded: {} trigger term(s) (artifact={})",
            kind,
            loaded.terms.size,
            loaded.version,
        )
        return loaded
    }

    /**
     * The rows for THIS kernel. Filtered on [TargetClass.GROUNDING_TRIGGER] *and* the ref, not on
     * the ref alone: the class is what the artifact states (RV-38), and re-deriving it from the
     * prefix here would be a second rule quietly free to diverge from the compiler's.
     */
    private fun sliceOf(lexicon: CompiledLexicon): GroundingSlice {
        val ref = "$GROUND_PREFIX$kind"
        val terms =
            lexicon.entries
                .asSequence()
                .filter { it.targetClass == TargetClass.GROUNDING_TRIGGER && it.targetRef == ref }
                .map { row ->
                    GroundingTerm(
                        folded =
                            org.tatrman.text.Normalization
                                .fold(row.termNormalized),
                        text = row.termNormalized,
                        method = TriggerMethod.parse(row.method),
                        lang = row.lang,
                    )
                }.toList()
        return GroundingSlice(kind, terms, lexicon.contentHash)
    }

    companion object {
        const val GROUND_PREFIX: String = "ground:"

        /** A source that will never load anything — the configured-off deployment, and tests. */
        fun disabled(kind: String): GroundingSliceSource = GroundingSliceSource(kind, null)
    }
}
