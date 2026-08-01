// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * Renders a column qname as the catalog category key (`db.<namespace>.<name>`).
 * The schema token mirrors `org.tatrman.plan.v1.schemaCodeToToken` but is kept
 * local so the loader doesn't depend on the proto-helper's exact surface.
 */
fun QualifiedName.toCategoryString(): String {
    val schemaToken =
        when (schemaCode) {
            SchemaCode.DB -> "db"
            SchemaCode.ER -> "er"
            SchemaCode.CNC -> "cnc"
            else -> "unknown"
        }
    return "$schemaToken.$namespace.$name"
}
