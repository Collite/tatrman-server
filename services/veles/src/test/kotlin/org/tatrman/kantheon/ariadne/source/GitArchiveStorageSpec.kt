package org.tatrman.kantheon.ariadne.source

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class GitArchiveStorageSpec :
    StringSpec({

        fun makeLocalRepo(
            seedFiles: Map<String, String>,
            branch: String = "main",
        ): Path {
            val workTree = Files.createTempDirectory("git-src-")
            workTree.toFile().deleteOnExit()
            val git =
                Git
                    .init()
                    .setDirectory(workTree.toFile())
                    .setInitialBranch(branch)
                    .call()
            git.use {
                for ((path, content) in seedFiles) {
                    val p = workTree.resolve(path)
                    p.parent.createDirectories()
                    p.writeText(content)
                    it.add().addFilepattern(path).call()
                }
                it
                    .commit()
                    .setMessage("seed")
                    .setSign(false)
                    .setAuthor(PersonIdent("test", "test@example.com"))
                    .setCommitter(PersonIdent("test", "test@example.com"))
                    .call()
            }
            return workTree
        }

        fun commitNew(
            repo: Path,
            path: String,
            content: String,
        ) {
            val p = repo.resolve(path)
            p.parent.createDirectories()
            p.writeText(content)
            Git.open(repo.toFile()).use { git ->
                git.add().addFilepattern(path).call()
                git
                    .commit()
                    .setMessage("update $path")
                    .setSign(false)
                    .setAuthor(PersonIdent("test", "test@example.com"))
                    .setCommitter(PersonIdent("test", "test@example.com"))
                    .call()
            }
        }

        "first load clones the repo and lists tracked files" {
            val src =
                makeLocalRepo(
                    mapOf(
                        "model/customers.ttr" to "schema db\ndef table customers {}\n",
                        "model/README.md" to "ignore me",
                    ),
                )
            val checkout = Files.createTempDirectory("git-checkout-")
            checkout.toFile().deleteOnExit()
            val storage = GitArchiveStorage(id = "g1", remoteUri = src.toUri().toString(), localCheckoutDir = checkout)

            val version = storage.fetchVersion()
            version.shouldNotBeEmpty()
            val files = storage.listFiles(extensions = listOf("ttr"))
            files shouldHaveAtLeastSize 1
            files.first().path.endsWith("customers.ttr") shouldBe true
            storage.read(files.first()).contains("def table customers") shouldBe true
        }

        "subsequent fetchVersion picks up new commits and updates the version stamp" {
            val src = makeLocalRepo(mapOf("model/a.ttr" to "schema db\ndef table a {}\n"))
            val checkout = Files.createTempDirectory("git-checkout-")
            checkout.toFile().deleteOnExit()
            val storage = GitArchiveStorage("g1", src.toUri().toString(), checkout)

            val v1 = storage.fetchVersion()
            commitNew(src, "model/b.ttr", "schema db\ndef table b {}\n")
            val v2 = storage.fetchVersion()

            (v1 != v2) shouldBe true
            val files = storage.listFiles(extensions = listOf("ttr"))
            files.map { it.path.substringAfterLast('/') }.toSet() shouldBe setOf("a.ttr", "b.ttr")
        }

        "files carry the work-tree root so package derivation matches the directory" {
            // Regression: GitArchiveStorage.listFiles must set StorageFile.rootPath (like
            // LocalFsStorage), else computePackageFromPath returns "" and every packaged file trips
            // ttr/package-declaration-mismatch (declared 'artikl' != directory computed '').
            val src =
                makeLocalRepo(
                    mapOf(
                        "artikl/db.ttr" to
                            """
                            package artikl
                            schema db namespace dbo

                            def table t {
                                primaryKey: ["id"]
                                columns: [
                                    def column id { type: int, isKey: true }
                                ]
                            }
                            """.trimIndent(),
                    ),
                )
            val checkout = Files.createTempDirectory("git-checkout-")
            checkout.toFile().deleteOnExit()
            val storage = GitArchiveStorage(id = "g1", remoteUri = src.toUri().toString(), localCheckoutDir = checkout)

            val snapshot = FileBasedSource(sourceId = "g1", priority = 100, storage = storage).load()

            snapshot.loadedFiles.single().computedPackage shouldBe "artikl"
            val mismatches = snapshot.errors.filter { it.message.contains("package-declaration-mismatch") }
            mismatches shouldBe emptyList()
        }

        "subdirectory limits the walk to that path" {
            val src =
                makeLocalRepo(
                    mapOf(
                        "config/skipme.ttr" to "schema db\n",
                        "model/customers.ttr" to "schema db\ndef table customers {}\n",
                    ),
                )
            val checkout = Files.createTempDirectory("git-checkout-")
            checkout.toFile().deleteOnExit()
            val storage =
                GitArchiveStorage(
                    id = "g1",
                    remoteUri = src.toUri().toString(),
                    localCheckoutDir = checkout,
                    subdirectory = "model",
                )
            storage.fetchVersion()
            val files = storage.listFiles(extensions = listOf("ttr"))
            files.map { it.path.substringAfterLast('/') }.toSet() shouldBe setOf("customers.ttr")
        }
    })
