package org.tatrman.testkit

import io.kotest.core.annotation.Condition
import io.kotest.core.spec.Spec
import kotlin.reflect.KClass

/**
 * Kotest [Condition] that enables a spec only in CI (or on explicit local
 * opt-in). Used to gate the **amd64-only MSSQL** component spec so it:
 *   - **always runs in CI** — `CI` is set by GitHub Actions on a native amd64
 *     runner, where the MSSQL image is native;
 *   - **never runs on the dev laptop** under `just test-component` — Apple
 *     Silicon would need slow amd64 emulation; the spec reports *skipped*, not
 *     failed;
 *   - **can be forced locally** with `-DmssqlLocal` for the rare time you do
 *     want to run it under emulation.
 *
 * Decision: Bora, 2026-06-19 (testing architecture §9). Apply with
 * `@EnabledIf(CiOnly::class)` on the spec class.
 */
class CiOnly : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean =
        System.getenv("CI") != null || System.getProperty("mssqlLocal") != null
}
