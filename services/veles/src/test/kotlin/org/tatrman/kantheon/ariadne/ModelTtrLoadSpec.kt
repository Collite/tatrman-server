package org.tatrman.kantheon.ariadne

import org.tatrman.kantheon.ariadne.model.ModelDescriptor
import org.tatrman.kantheon.ariadne.reconcile.ModelReconciler
import org.tatrman.kantheon.ariadne.source.BuiltinStockSource
import org.tatrman.kantheon.ariadne.source.ClasspathStorage
import org.tatrman.kantheon.ariadne.source.FileBasedSource
import org.tatrman.kantheon.ariadne.source.LocalFsStorage
import org.tatrman.kantheon.ariadne.source.ModelStorage
import org.tatrman.kantheon.ariadne.source.StorageFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.nio.file.Path

/**
 * Guards the committed `model-ttr` package tree (output of `just yaml-to-ttr`): a complete
 * package must reload through the real `FileBasedSource → ModelReconciler` path — alongside
 * `BuiltinStockSource` (stock roles are re-supplied at import) — with no resolution errors.
 * Cross-package references are fully-qualified and resolve via the resolver's exact `byFull`
 * step (no imports needed).
 *
 * **Scope: `ucetnictvi`.** The full committed tree is *not* asserted error-free because some
 * packages in the converted snapshot are incomplete — their relations reference entity
 * definitions (`er.entity.artikl`, `produkt`, …) that are missing from the seed (e.g. the
 * `artikl` package ships `db.ttr` + `er.ttr` relations but no `def entity`). That is a real
 * seed-completeness gap tracked for fixup (kantheon-v1.1 §8 / fork Stage 2.1 T2 — complete the
 * `model-ttr` seed). `ucetnictvi` is self-contained (every `er.entity.*` it references is
 * defined within it), so it is the stable guard for the loader/reconciler path until the seed
 * is completed; widen [SEED_PACKAGES] as packages are made whole.
 *
 * Package derivation relativises each file against the storage root, so the storage root stays
 * the `model-ttr` tree (pointing it straight at a package subdir would compute an empty package
 * and trip `ttr/package-declaration-mismatch`); [ScopedStorage] filters that root down to the
 * in-scope package(s) without disturbing derivation.
 */
class ModelTtrLoadSpec :
    StringSpec({

        "a complete package (ucetnictvi) reconciles with no errors via LocalFsStorage" {
            // Test working dir is the module dir.
            val root = Path.of("src/main/resources/model-ttr")
            val storage = ScopedStorage(LocalFsStorage(id = "model-ttr", rootPath = root), SEED_PACKAGES)
            val source = FileBasedSource(sourceId = "model-ttr", priority = 100, storage = storage)
            val result =
                ModelReconciler(ModelDescriptor(id = "m", name = "m"))
                    .reconcile(listOf(BuiltinStockSource().load(), source.load()))

            // No blocking diagnostics: refs resolve, packages match their directories, kinds fit.
            result.errors shouldHaveSize 0
            // Sanity: the package actually loaded a non-trivial number of objects.
            result.model.objectByQname().size shouldBeGreaterThan 100
        }

        "a complete package (ucetnictvi) loads cleanly via the service path (ClasspathStorage resources + ttr)" {
            // Mirrors application.conf: type=resources kind=ttr path=model-ttr (+ builtin stock),
            // scoped to the complete package via ScopedStorage.
            val storage = ScopedStorage(ClasspathStorage(id = "model-ttr", resourcePrefix = "model-ttr"), SEED_PACKAGES)
            val source = FileBasedSource(sourceId = "model-ttr", priority = 100, storage = storage)
            val result =
                ModelReconciler(ModelDescriptor(id = "m", name = "m"))
                    .reconcile(listOf(BuiltinStockSource().load(), source.load()))

            result.errors shouldHaveSize 0
            result.model.objectByQname().size shouldBeGreaterThan 100
        }
    })

/**
 * Package(s) of the committed `model-ttr` seed that are complete (every referenced entity is
 * defined). Widen as the incomplete packages (artikl, produkt, …) gain their missing
 * `def entity` files — see the spec KDoc / kantheon-v1.1 §8.
 */
private val SEED_PACKAGES = setOf("ucetnictvi")

/**
 * [ModelStorage] decorator that delegates to a real storage rooted at the whole `model-ttr`
 * tree (so per-file package derivation stays correct) but exposes only files under the named
 * package directories.
 */
private class ScopedStorage(
    private val delegate: ModelStorage,
    private val packages: Set<String>,
) : ModelStorage {
    override val id: String = delegate.id

    override fun fetchVersion(): String = delegate.fetchVersion()

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> =
        delegate
            .listFiles(extensions, prefixes)
            .filter { file -> packages.any { pkg -> file.path.contains("/model-ttr/$pkg/") } }

    override fun read(file: StorageFile): String = delegate.read(file)
}
