package org.tatrman.testkit.integration

import io.kotest.assertions.withClue
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Stage 2.1 T4 — cross-repo drift guard. Scans every integration spec for
 * `@RequiresContext("<name>")` and asserts each `<name>` has a matching
 * `test-contexts/<name>/context.yaml` in the olymp checkout. Catches a
 * renamed/missing context before the nightly does (testing contracts §2).
 *
 * Runs in the **component** tier (no cluster), but only where an olymp checkout
 * is provided (`-PolympDir=`); it skips on a plain PR component run. Only
 * `src/integrationTest/` is scanned, so unit-test fixtures that use the
 * annotation with throwaway names don't register as orphans.
 */
@Tags("component")
@EnabledIf(OlympCheckoutPresent::class)
class ContextNameRegistrySpec :
    StringSpec({
        "every @RequiresContext name resolves to an olymp test-contexts/<name>/context.yaml" {
            val repoRoot = File(System.getProperty("integrationHarness.repoRoot") ?: ".")
            val olympDir = File(System.getProperty("olympDir")!!)
            val annotationRegex = Regex("""@RequiresContext\(\s*(?:name\s*=\s*)?"([^"]+)"""")

            val referenced =
                repoRoot
                    .walkTopDown()
                    .filter {
                        it.isFile &&
                            it.extension == "kt" &&
                            it.path.contains("${File.separator}src${File.separator}integrationTest${File.separator}")
                    }.flatMap { f -> annotationRegex.findAll(f.readText()).map { it.groupValues[1] } }
                    .toSortedSet()

            val orphans = referenced.filter { name -> !File(olympDir, "test-contexts/$name/context.yaml").exists() }

            withClue("@RequiresContext names with no olymp test-contexts/<name>/context.yaml under $olympDir") {
                orphans shouldBe emptyList()
            }
        }
    })
