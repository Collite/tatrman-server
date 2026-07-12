// SPDX-License-Identifier: Apache-2.0
package org.tatrman.identity.domain

import kotlinx.serialization.Serializable

@Serializable
enum class UserSource {
    KEYCLOAK,
    ERP,
    ENTRAID,
}
