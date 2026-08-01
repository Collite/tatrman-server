// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

/**
 * The five RPCs Charon exposes. The legality matrix (`Legality.kt`, service-side)
 * is keyed by an `MoveRpc` value, not a string, so adding a new RPC is a compile
 * error in the matrix (and a new row, not a hand-edit on the wire).
 *
 * See `docs/architecture/charon/contracts.md` §1 for the proto-side definition
 * of the same operations. Part of the published seam (`transfer-core`, CH-D5)
 * — an in-process consumer names the RPC when it builds a [Plan].
 */
enum class MoveRpc {
    MATERIALIZE,
    STAGE,
    COPY,
    EVICT,
    DESCRIBE,
}
