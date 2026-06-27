package org.tatrman.kantheon.ariadne.search

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.ariadne.model.Attribute
import org.tatrman.kantheon.ariadne.model.CncSchema
import org.tatrman.kantheon.ariadne.model.Entity
import org.tatrman.kantheon.ariadne.model.ErSchema
import org.tatrman.kantheon.ariadne.model.LocalizedText
import org.tatrman.kantheon.ariadne.model.Query
import org.tatrman.kantheon.ariadne.model.Role
import org.tatrman.kantheon.ariadne.model.SearchHints
import org.tatrman.kantheon.ariadne.registry.RegistrySnapshot

/**
 * Flat carrier for any model object the search feature consumes. Removes
 * the per-kind sealed-interface dance from algorithm code: every algorithm
 * iterates a `Sequence<IndexableObject>` and reads only what it needs.
 *
 * Kept inside the search package because the projection here is shaped
 * for search; other consumers should walk the model directly.
 */
internal data class IndexableObject(
    val qname: QualifiedName,
    val kind: String,
    val description: String,
    val displayLabel: LocalizedText,
    val search: SearchHints,
)

internal fun RegistrySnapshot.searchableObjects(): Sequence<IndexableObject> =
    sequence {
        for ((_, schema) in model.schemas) {
            when (schema) {
                is ErSchema -> {
                    for (e in schema.entities.values) yield(e.toIndexable())
                    for (e in schema.entities.values) for (a in e.attributes) yield(a.toIndexable())
                }
                is CncSchema -> for (r in schema.roles.values) yield(r.toIndexable())
                else -> {}
            }
        }
        for (q in model.queries.values) yield(q.toIndexable())
    }

private fun Entity.toIndexable(): IndexableObject = IndexableObject(qname, kind, description, displayLabel, search)

private fun Attribute.toIndexable(): IndexableObject = IndexableObject(qname, kind, description, displayLabel, search)

private fun Role.toIndexable(): IndexableObject = IndexableObject(qname, kind, description, label, search)

private fun Query.toIndexable(): IndexableObject =
    IndexableObject(qname, kind, description, LocalizedText.EMPTY, search)
