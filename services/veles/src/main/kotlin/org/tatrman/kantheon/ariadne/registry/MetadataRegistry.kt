package org.tatrman.kantheon.ariadne.registry

import org.tatrman.kantheon.ariadne.graph.ModelGraph
import org.tatrman.kantheon.ariadne.model.Model
import org.tatrman.kantheon.ariadne.source.LoadWarning
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds the current [RegistrySnapshot] under an `AtomicReference` for
 * lock-free reads. All writes go through [swap], which atomically replaces
 * the snapshot and notifies listeners.
 *
 * Per-request consumers should capture the snapshot once at the start of the
 * request and hold the reference for its lifetime — that way an in-flight
 * read isn't disturbed by a refresh-driven swap mid-call.
 */
class MetadataRegistry {
    private val ref = AtomicReference<RegistrySnapshot?>(null)
    private val listeners = CopyOnWriteArrayList<(RegistrySnapshot) -> Unit>()

    fun read(): RegistrySnapshot? = ref.get()

    fun swap(
        model: Model,
        graph: ModelGraph,
        lastWarnings: List<LoadWarning> = emptyList(),
    ) {
        val snapshot =
            RegistrySnapshot(
                model = model,
                graph = graph,
                swappedAt = Instant.now(),
                warnings = lastWarnings,
            )
        ref.set(snapshot)
        listeners.forEach { it.runCatching { invoke(snapshot) } }
    }

    fun addListener(listener: (RegistrySnapshot) -> Unit) {
        listeners += listener
    }

    /** Convenience for callers that require a snapshot to exist. */
    fun readOrError(): RegistrySnapshot = read() ?: error("MetadataRegistry has no snapshot — service is not ready yet")
}

data class RegistrySnapshot(
    val model: Model,
    val graph: ModelGraph,
    val swappedAt: Instant,
    val warnings: List<LoadWarning>,
)
