// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

/** An issued key: the plaintext (return-value only — never logged/stored) plus its persisted row. */
data class IssuedKey(
    val plaintext: String,
    val row: VirtualKeyRow,
)

/**
 * Key lifecycle service (D-1): mint → hash → persist, revoke, list. The plaintext exists ONLY in the
 * [IssuedKey] returned to the caller; storage sees the SHA-256 hex alone. HTTP surface lands in
 * LG-P4·S3 — this is the service layer, driven directly by tests.
 */
class KeyService(
    private val keys: VirtualKeyRepo,
    private val teams: TeamRepo,
) {
    /** Mint and persist a fresh key for [team]. Fails if the team is unknown (FK integrity, contracts §3). */
    fun issueKey(
        team: String,
        name: String,
        budgetUsd: Double? = null,
        rpmLimit: Int? = null,
    ): IssuedKey {
        require(teams.exists(team)) { "unknown team '$team' — teams are config-sourced (governance.yaml)" }
        val plaintext = KeyMint.generate()
        val row =
            keys.insert(
                teamId = team,
                name = name,
                keyHash = KeyMint.hash(plaintext),
                seeded = false,
                budgetUsd = budgetUsd,
                rpmLimit = rpmLimit,
            )
        return IssuedKey(plaintext, row)
    }

    fun revoke(keyId: String): Boolean = keys.revoke(keyId)

    fun list(team: String): List<VirtualKeyRow> = keys.listByTeam(team)
}
