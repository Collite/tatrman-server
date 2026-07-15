// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import shared.libs.db.common.DatabaseConnection
import java.time.OffsetDateTime

/** A `virtual_keys` row projection (no ORM entity). Overrides are nullable (min-wins, D-3). */
data class VirtualKeyRow(
    val id: String,
    val teamId: String,
    val name: String,
    val keyHash: String,
    val seeded: Boolean,
    val budgetUsd: Double?,
    val rpmLimit: Int?,
    val revokedAt: OffsetDateTime?,
    val lastUsedAt: OffsetDateTime?,
)

/**
 * Virtual-keys repo (Exposed DSL, contracts §3). Keys are stored as SHA-256 hex only (D-1); ids are
 * `vk_<ulid>`. `find*` is by hash (the validator's lookup); revoke/list/touch support the key lifecycle
 * and the S3 admin surface. All timestamps are written UTC via [now].
 */
class VirtualKeyRepo(
    private val db: DatabaseConnection,
    private val now: () -> OffsetDateTime = ::utcNow,
) {
    /** Insert an issued (non-seeded) key. Caller supplies the already-hashed key (plaintext never enters). */
    fun insert(
        teamId: String,
        name: String,
        keyHash: String,
        seeded: Boolean = false,
        budgetUsd: Double? = null,
        rpmLimit: Int? = null,
    ): VirtualKeyRow {
        val newId = "vk_" + Ulid.next()
        db.query {
            VirtualKeys.insert {
                it[id] = newId
                it[VirtualKeys.teamId] = teamId
                it[VirtualKeys.name] = name
                it[VirtualKeys.keyHash] = keyHash
                it[VirtualKeys.seeded] = seeded
                it[VirtualKeys.budgetUsd] = budgetUsd?.toBigDecimal()
                it[VirtualKeys.rpmLimit] = rpmLimit
            }
        }
        return VirtualKeyRow(newId, teamId, name, keyHash, seeded, budgetUsd, rpmLimit, null, null)
    }

    /** Lookup by SHA-256 hex (key_hash is UNIQUE). Returns the row regardless of revocation. */
    fun findByHash(keyHash: String): VirtualKeyRow? =
        db.query {
            VirtualKeys
                .selectAll()
                .where { VirtualKeys.keyHash eq keyHash }
                .singleOrNull()
                ?.toRow()
        }

    /**
     * Idempotent seeded import (G-3): if the hash already exists, keep the existing row (re-boot no-ops);
     * otherwise insert a fresh `seeded=true` row. Returns the row id either way.
     */
    fun upsertSeeded(
        teamId: String,
        name: String,
        keyHash: String,
    ): String =
        db.query {
            val existing = VirtualKeys.selectAll().where { VirtualKeys.keyHash eq keyHash }.singleOrNull()
            if (existing != null) {
                existing[VirtualKeys.id]
            } else {
                val newId = "vk_" + Ulid.next()
                VirtualKeys.insert {
                    it[id] = newId
                    it[VirtualKeys.teamId] = teamId
                    it[VirtualKeys.name] = name
                    it[VirtualKeys.keyHash] = keyHash
                    it[seeded] = true
                }
                newId
            }
        }

    /** Stamp `revoked_at` (UTC). Returns true if a row was updated. Re-revoking simply re-stamps. */
    fun revoke(id: String): Boolean =
        db.query { VirtualKeys.update({ VirtualKeys.id eq id }) { it[revokedAt] = now() } > 0 }

    fun listByTeam(teamId: String): List<VirtualKeyRow> =
        db.query { VirtualKeys.selectAll().where { VirtualKeys.teamId eq teamId }.map { it.toRow() } }

    /** Throttled by the validator to ≤1 write/key/minute — the repo write itself is unconditional. */
    fun touchLastUsed(id: String) {
        db.query { VirtualKeys.update({ VirtualKeys.id eq id }) { it[lastUsedAt] = now() } }
    }

    private fun ResultRow.toRow(): VirtualKeyRow =
        VirtualKeyRow(
            id = this[VirtualKeys.id],
            teamId = this[VirtualKeys.teamId],
            name = this[VirtualKeys.name],
            keyHash = this[VirtualKeys.keyHash],
            seeded = this[VirtualKeys.seeded],
            budgetUsd = this[VirtualKeys.budgetUsd]?.toDouble(),
            rpmLimit = this[VirtualKeys.rpmLimit],
            revokedAt = this[VirtualKeys.revokedAt],
            lastUsedAt = this[VirtualKeys.lastUsedAt],
        )
}
