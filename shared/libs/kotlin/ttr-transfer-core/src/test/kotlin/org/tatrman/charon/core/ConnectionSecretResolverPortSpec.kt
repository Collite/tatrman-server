// SPDX-License-Identifier: Apache-2.0
package org.tatrman.charon.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs

/**
 * The published lib's own gate for the CH-D3 secret-resolution port (review-075 F5).
 *
 * The lib ships no `META-INF/services/…ConnectionSecretResolver`, so `discover()`
 * here exercises the dependency-free default path exactly as an open deployment
 * with no adapter mounted would — the behaviour the port promises to keep.
 */
class ConnectionSecretResolverPortSpec :
    StringSpec({
        "discover() with no registered provider returns the env-var default" {
            // No provider on the lib's own test classpath → the open, dependency-free default.
            ConnectionSecretResolver.discover() shouldBeSameInstanceAs EnvSecretResolver
        }

        "discover(requireProvider = true) with no provider fails closed" {
            // The require-half (review-074 F5): a deployment that MUST bind a real store
            // refuses to fall back to env binding rather than surface as "connections degraded".
            val ex = shouldThrow<IllegalStateException> { ConnectionSecretResolver.discover(requireProvider = true) }
            ex.message shouldContain "require-secret-resolver"
        }

        "EnvSecretResolver resolves a set token and leaves an unset one null" {
            // PATH is present on every platform the JVM runs on; a random name is not.
            EnvSecretResolver.resolve("PATH") shouldBe System.getenv("PATH")
            EnvSecretResolver.resolve("TTR_CONN_DEFINITELY_UNSET_XYZZY") shouldBe null
        }
    })
