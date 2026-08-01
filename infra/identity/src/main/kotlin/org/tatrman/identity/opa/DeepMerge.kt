// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.opa

import kotlinx.serialization.json.JsonObject

/**
 * Recursively merges two kotlinx.serialization JsonObjects.
 * The 'update' object takes precedence in case of non-object conflicts.
 */
fun deepMerge(
    main: JsonObject,
    update: JsonObject,
): JsonObject {
    val mergedMap = main.toMutableMap()

    for ((key, updateValue) in update) {
        val mainValue = main[key]

        if (mainValue is JsonObject && updateValue is JsonObject) {
            // Both are objects, merge them recursively
            mergedMap[key] = deepMerge(mainValue, updateValue)
        } else {
            // Overwrite or insert the new value
            mergedMap[key] = updateValue
        }
    }

    return JsonObject(mergedMap)
}
