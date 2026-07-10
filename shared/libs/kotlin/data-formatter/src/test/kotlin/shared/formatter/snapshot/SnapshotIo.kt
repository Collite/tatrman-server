package shared.formatter.snapshot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * File-system helpers for byte-stable snapshot tests.
 *
 * Resource paths resolve against src/test/resources (compiled into
 * build/resources/test). When environment variable `REGENERATE_SNAPSHOTS=1`
 * is set, [assertEqualsOrRegenerate] writes actual bytes to
 * src/test/resources/<relativePath> instead of asserting.
 *
 * Workflow:
 *   REGENERATE_SNAPSHOTS=1 ./gradlew :shared:libs:kotlin:data-formatter:test
 *   git add shared/libs/kotlin/data-formatter/src/test/resources
 *   ./gradlew :shared:libs:kotlin:data-formatter:test   # asserts.
 */
internal object SnapshotIo {
    private val regenerate: Boolean = System.getenv("REGENERATE_SNAPSHOTS") == "1"

    fun read(relativePath: String): ByteArray {
        val stream =
            this::class.java.classLoader.getResourceAsStream(relativePath)
                ?: error("snapshot resource missing: $relativePath (set REGENERATE_SNAPSHOTS=1 to create it)")
        return stream.use { it.readAllBytes() }
    }

    fun assertEqualsOrRegenerate(
        relativePath: String,
        actual: ByteArray,
        moduleRootSubpath: String = "shared/libs/kotlin/data-formatter",
    ) {
        if (regenerate) {
            val cwd = Paths.get("").toAbsolutePath()
            val moduleRoot = locateModuleRoot(cwd, moduleRootSubpath)
            val target: Path = moduleRoot.resolve("src/test/resources").resolve(relativePath)
            Files.createDirectories(target.parent)
            Files.write(target, actual)
            println("  [snapshot] regenerated $relativePath (${actual.size} bytes)")
            return
        }
        val expected = read(relativePath)
        if (!expected.contentEquals(actual)) {
            val actualText = runCatching { String(actual, Charsets.UTF_8) }.getOrDefault("<binary>")
            val expectedText = runCatching { String(expected, Charsets.UTF_8) }.getOrDefault("<binary>")
            error(
                buildString {
                    appendLine("Snapshot mismatch for $relativePath")
                    appendLine("Expected (${expected.size} bytes):")
                    appendLine(expectedText)
                    appendLine("Actual (${actual.size} bytes):")
                    appendLine(actualText)
                },
            )
        }
    }

    private fun locateModuleRoot(
        start: Path,
        moduleRootSubpath: String,
    ): Path {
        // Walk up from the test working dir until we find a folder that *is* the module root.
        var current: Path? = start
        while (current != null) {
            val candidate = current.resolve(moduleRootSubpath)
            if (Files.exists(candidate.resolve("build.gradle.kts"))) return candidate
            // Or current already is the module root.
            if (Files.exists(current.resolve("build.gradle.kts")) &&
                current.toString().endsWith(moduleRootSubpath.replace('/', java.io.File.separatorChar))
            ) {
                return current
            }
            current = current.parent
        }
        // Fallback: assume cwd already is the module root (Gradle default for per-module test working dir).
        return start
    }
}
