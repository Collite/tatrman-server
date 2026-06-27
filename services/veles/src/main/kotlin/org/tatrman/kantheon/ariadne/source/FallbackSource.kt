package org.tatrman.kantheon.ariadne.source

import org.slf4j.LoggerFactory

/**
 * Composite [ModelSource] that serves [primary] when it loads a usable model, and otherwise falls
 * back to [fallback]. The two are **mutually exclusive** — never merged — so a stale bundled
 * fallback can't resurrect definitions the primary (live repo) has since deleted.
 *
 * Used to make the `Tatrman/ai-models` GitHub repo the live model source (via [GitArchiveStorage])
 * while keeping the bundled `resources/model-ttr` copy as a pure fallback for when the repo is
 * unreachable or unconfigured.
 *
 * "Primary failed" means either [ModelSource.load] threw (clone / auth / network failure) or it
 * returned an **unusable** snapshot (empty across every object map — e.g. a misconfigured remote or
 * empty subdirectory). A primary that loads with some parse warnings/errors but real content is used
 * as-is, matching how a resources-backed model already behaves.
 *
 * The returned snapshot keeps whichever source's own [SourceSnapshot.sourceId] / [SourceSnapshot.version]
 * won. That is deliberate: the refresher tracks this composite under its slot id ([primaryId]) and the
 * scheduler short-circuits on an unchanged version, so the version flipping git-SHA ⇄ bundled-static as
 * the primary fails/recovers is exactly the signal that triggers (or skips) a reconcile.
 */
class FallbackSource(
    private val primaryId: String,
    private val primary: ModelSource,
    private val fallback: ModelSource,
) : ModelSource {
    private val log = LoggerFactory.getLogger(FallbackSource::class.java)

    override fun load(): SourceSnapshot {
        val primarySnapshot =
            try {
                primary.load().takeIf { it.isUsable() }
            } catch (e: Exception) {
                log.error("Primary model source '{}' failed to load; serving bundled fallback", primaryId, e)
                null
            }
        if (primarySnapshot != null) {
            log.info("Primary model source '{}' loaded (version={})", primaryId, primarySnapshot.version)
            return primarySnapshot
        }

        val fb = fallback.load()
        log.warn(
            "Primary model source '{}' unavailable — serving bundled fallback '{}' (version={})",
            primaryId,
            fb.sourceId,
            fb.version,
        )
        return fb.copy(
            warnings =
                fb.warnings +
                    LoadWarning(
                        sourceId = primaryId,
                        file = "<fallback>",
                        line = 0,
                        column = 0,
                        message =
                            "Primary source '$primaryId' unavailable — serving bundled fallback model '${fb.sourceId}'",
                    ),
        )
    }
}

/** A snapshot is usable if it contributed at least one model object of any kind. */
private fun SourceSnapshot.isUsable(): Boolean =
    tables.isNotEmpty() ||
        views.isNotEmpty() ||
        procedures.isNotEmpty() ||
        foreignKeys.isNotEmpty() ||
        entities.isNotEmpty() ||
        relations.isNotEmpty() ||
        mappings.isNotEmpty() ||
        queries.isNotEmpty() ||
        roles.isNotEmpty() ||
        drillMaps.isNotEmpty()
