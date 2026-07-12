// SPDX-License-Identifier: Apache-2.0
package org.tatrman.testkit.integration

/**
 * Marks a Kotest spec as needing a named integration context to be live. The
 * [RequiresContextExtension] asserts the context is up before the spec runs and
 * **fails fast** otherwise — it never provisions. The [name] is the single
 * cross-repo contract key (testing contracts §2): it must match an olymp
 * `test-contexts/<name>/` (enforced by `ContextNameRegistrySpec`).
 *
 * **The extension is NOT auto-registered.** Kotest 6.0 removed classpath scanning
 * (`@AutoScan`), so the gate only runs if you also apply it on the spec:
 *
 * ```
 * @RequiresContext("query-runquery")
 * @ApplyExtension(RequiresContextExtension::class)
 * class RunQueryIntegrationSpec : StringSpec({ ... })
 * ```
 *
 * Omit `@ApplyExtension` and the spec runs with **no gate** (no readiness check,
 * no `ContextHandle`) — [Spec.contextHandle] then fails loudly with a reminder.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresContext(
    val name: String,
)
