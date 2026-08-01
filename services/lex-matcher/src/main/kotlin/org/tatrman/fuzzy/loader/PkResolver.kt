// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.meta.v1.DbTableDetail

/** The single PK column, or null when the table has zero or composite keys. */
fun singleColumnPkOrNull(table: DbTableDetail): String? {
    val pkCount = table.primaryKeyCount
    return if (pkCount == 1) table.getPrimaryKey(0) else null
}

/** Reason a table is unusable for `SELECT pk, col` — `no_pk` / `composite_pk`, or null when fine. */
fun pkReason(table: DbTableDetail): String? {
    val pkCount = table.primaryKeyCount
    return when {
        pkCount == 0 -> "no_pk"
        pkCount > 1 -> "composite_pk"
        else -> null
    }
}
