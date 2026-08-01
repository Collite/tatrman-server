// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway

import org.tatrman.llmgateway.auth.sha256Hex
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey

/**
 * Test fixtures for the authenticated data plane: a known `ttrk-` key seeded into the loaded governance
 * so component/test-host specs run authenticated (the same seam Bora uses at cutover via `governance.yaml`).
 */
object TestSupport {
    const val SEEDED_KEY = "ttrk-test-seed-key-0000000000000000000000"

    /** The real config, with [SEEDED_KEY]'s SHA-256 added as a seeded key for team `golem`. */
    fun seededGateway(): GatewayConfig {
        val base = ConfigLoader.loadFromResources()
        return base.copy(
            governance =
                base.governance.copy(
                    keys =
                        base.governance.keys +
                            SeededKey(team = "golem", name = "test-key", sha256 = sha256Hex(SEEDED_KEY)),
                ),
        )
    }
}
