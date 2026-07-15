// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** UTC-now as an `OffsetDateTime` — every governance timestamp is `timestamptz`, written UTC (contracts §3). */
internal fun utcNow(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

/**
 * Exposed table objects for the governance domain (`V2__governance.sql`, contracts §3). DSL only — no ORM
 * entities (house rule). Timestamps are `timestamptz`; `created_at` carries a client default so inserts
 * never depend on the DB `DEFAULT now()`.
 */
object Teams : Table("teams") {
    val id = text("id")
    val costCenterPrefix = text("cost_center_prefix")
    val createdAt = timestampWithTimeZone("created_at").clientDefault { utcNow() }

    override val primaryKey = PrimaryKey(id)
}

object VirtualKeys : Table("virtual_keys") {
    val id = text("id") // vk_<ulid>
    val teamId = text("team_id").references(Teams.id)
    val name = text("name")
    val keyHash = text("key_hash").uniqueIndex() // SHA-256 hex; plaintext never stored (D-1)
    val seeded = bool("seeded")
    val budgetUsd = decimal("budget_usd", 12, 6).nullable() // per-key override (min-wins, D-3)
    val rpmLimit = integer("rpm_limit").nullable()
    val createdAt = timestampWithTimeZone("created_at").clientDefault { utcNow() }
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
