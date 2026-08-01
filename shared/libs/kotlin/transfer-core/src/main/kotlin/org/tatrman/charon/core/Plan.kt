// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import org.tatrman.transfer.v1.Location
import org.tatrman.transfer.v1.MoveOptions

/**
 * A [Plan] is what the planner hands to the [MoveExecutor] when the legality
 * check passes. The executor still has to *do* the move — the plan is the
 * validated spec, not the result.
 *
 * Kept as a small data class (one public class per file, `AGENTS.md` §9) —
 * additional fields land in later stages (the connection registry handle, the
 * resolved worker endpoint address, the per-RPC deadline, etc.).
 *
 * Lives in `transfer-core` (the published seam, CH-D5): it is part of the
 * [MoveExecutor] contract an in-process consumer constructs directly. The
 * planner that *produces* validated `Plan`s (`MovePlanner`) stays service-side.
 */
data class Plan(
    val rpc: MoveRpc,
    val source: Location,
    val target: Location?,
    val options: MoveOptions,
)
