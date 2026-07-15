// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.tatrman.llmgateway.config.Team
import shared.libs.db.common.DatabaseConnection

/**
 * Teams repo (Exposed DSL). `governance.yaml` is the source of truth for teams (contracts §3); rows are
 * upserted at startup purely for FK integrity so issued/seeded keys can reference them.
 */
class TeamRepo(
    private val db: DatabaseConnection,
) {
    /** Upsert every config team (insert new, refresh `cost_center_prefix` on existing). Idempotent. */
    fun upsertAll(teams: List<Team>) {
        db.query {
            teams.forEach { t ->
                val present = Teams.selectAll().where { Teams.id eq t.id }.any()
                if (present) {
                    Teams.update({ Teams.id eq t.id }) { it[costCenterPrefix] = t.costCenterPrefix }
                } else {
                    Teams.insert {
                        it[id] = t.id
                        it[costCenterPrefix] = t.costCenterPrefix
                    }
                }
            }
        }
    }

    fun exists(teamId: String): Boolean = db.query { Teams.selectAll().where { Teams.id eq teamId }.any() }
}
