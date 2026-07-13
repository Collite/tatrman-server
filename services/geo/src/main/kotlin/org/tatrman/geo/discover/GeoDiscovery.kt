// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.discover

import org.tatrman.plan.v1.QualifiedName

/** An ER column resolved from metadata: its qname plus the owning-entity + column names. */
data class ColumnRef(
    val qname: QualifiedName,
    val entityName: String,
    val columnName: String,
)

/** The queried entity's WGS84 coordinate columns (`geo_lat` / `geo_lon` roles). */
data class GeoColumns(
    val lat: ColumnRef,
    val lon: ColumnRef,
)

/**
 * A POI entity in the model (`semantics{kind: poi}`): its qname + display name-attribute + WGS84
 * coordinate columns. An anchor that names a POI instance is grounded by JOINing this entity on
 * `nameColumn = {place}` and reading its [latColumn]/[lonColumn] at query time — the service never
 * reads business rows (the POI-in-model layer).
 */
data class PoiEntity(
    val entity: QualifiedName,
    val entityName: String,
    val nameColumn: String,
    val latColumn: String,
    val lonColumn: String,
)

/**
 * The metadata seam (RG-P3.S0.T5 / G2 closure) — geo's discovery as a **domain-typed port**.
 * GeoDiscovery returns the recipe layer's domain types ([GeoColumns]/[PoiEntity]/[ColumnRef]); the
 * raw `org.tatrman.meta.v1` reads live behind a single adapter ([MetaV1GeoDiscovery]), so the recipe
 * layer, the POI resolver, and every grounding test stay proto-free and back it with domain objects.
 */
interface GeoDiscovery {
    /** lat + lon columns of the package's geo entity, or null when either role is absent. */
    suspend fun geoColumns(pkg: String): GeoColumns?

    /** The package's POI entity (`kind: poi`) with its name attribute + geo columns, or null. */
    suspend fun poiEntity(pkg: String): PoiEntity?

    /** Liveness probe surfaced through GetStatus (the metadata backend's `model_loaded`). */
    suspend fun probeReady(): Boolean
}

/** Fixture/degrade discovery — ready, but surfaces nothing (no grounding without real metadata). */
object EmptyGeoDiscovery : GeoDiscovery {
    override suspend fun geoColumns(pkg: String): GeoColumns? = null

    override suspend fun poiEntity(pkg: String): PoiEntity? = null

    override suspend fun probeReady(): Boolean = true
}
