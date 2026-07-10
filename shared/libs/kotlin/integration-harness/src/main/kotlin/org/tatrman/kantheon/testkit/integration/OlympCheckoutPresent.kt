package org.tatrman.kantheon.testkit.integration

import io.kotest.core.annotation.Condition
import io.kotest.core.spec.Spec
import java.io.File
import kotlin.reflect.KClass

/**
 * Kotest [Condition] enabling a spec only when an olymp checkout is available
 * (`-PolympDir=` → sysprop `olympDir`). The cross-repo drift guard
 * (`ContextNameRegistrySpec`) needs olymp's `test-contexts/` to validate against,
 * so it skips cleanly on a PR component run that has no olymp checkout, and runs
 * where olymp is checked out (the nightly / a dedicated step).
 */
class OlympCheckoutPresent : Condition {
    override fun evaluate(kclass: KClass<out Spec>): Boolean {
        val dir = System.getProperty("olympDir") ?: return false
        return File(dir).isDirectory
    }
}
