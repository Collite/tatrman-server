// SPDX-License-Identifier: Apache-2.0
package org.tatrman.keycloak.auth

interface TokenProvider {
    suspend fun getToken(): String
}
