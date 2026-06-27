package org.tatrman.kantheon.ariadne.source

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * DF-M09 / Phase 07 C1 — `ModelStorage` backed by a git repository. On first load (when the
 * local checkout doesn't exist or is empty) we **clone** the configured `remoteUri` into
 * `localCheckoutDir`; on subsequent loads we **fetch + hard-reset** to the latest commit of
 * `branch` so the metadata service tracks remote changes the same way a CI deploy would.
 *
 * Once the working tree is up to date, [listFiles] / [read] / [fetchVersion] delegate to the
 * same recursive-file logic as [LocalFsStorage] — same parser surface, no special-casing in the
 * rest of the pipeline.
 *
 * **Version stamp** = the resolved commit SHA. Changes only when the remote moves, so the
 * scheduler's no-change short-circuit (Phase 07 B2) avoids unnecessary reconciles on quiet
 * intervals.
 *
 * Authentication: if [username] is set, JGit's [UsernamePasswordCredentialsProvider] is used —
 * works for HTTPS with a PAT / app token (set `username = "x-access-token"` for GitHub-style
 * tokens). SSH auth needs the platform's SSH agent or a configured `~/.ssh/config`; out of scope
 * for v1 — open as a follow-up if a deployment needs it.
 *
 * Concurrency: [fetchVersion] / [listFiles] / [read] are called from the metadata refresher
 * which already serialises refreshes; this class doesn't add its own locking.
 */
class GitArchiveStorage(
    override val id: String,
    private val remoteUri: String,
    private val localCheckoutDir: Path,
    private val branch: String = "main",
    private val subdirectory: String = "",
    private val username: String? = null,
    private val password: String? = null,
) : ModelStorage {
    private val log = LoggerFactory.getLogger(GitArchiveStorage::class.java)

    @Volatile
    private var lastCommitSha: String = ""

    override fun fetchVersion(): String {
        ensureUpToDate()
        return lastCommitSha
    }

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> {
        ensureUpToDate()
        val root = workTreeRoot()
        if (!Files.isDirectory(root)) return emptyList()
        return Files
            .walk(root)
            .filter { file ->
                Files.isRegularFile(file) &&
                    extensions.any { ext -> file.fileName.toString().endsWith(".$ext") } &&
                    (prefixes.isEmpty() || prefixes.any { prefix -> file.fileName.toString().startsWith(prefix) })
            }.sorted()
            // rootPath anchors per-file package derivation (computePackageFromPath relativises the
            // file against it). Without it the computed package is "" and every packaged file trips
            // ttr/package-declaration-mismatch. Same contract as LocalFsStorage's listFilesMatching.
            .map { StorageFile(path = it.toString(), sizeBytes = Files.size(it), rootPath = root) }
            .toList()
    }

    override fun read(file: StorageFile): String = Files.readString(Path.of(file.path))

    private fun ensureUpToDate() {
        val gitDir = localCheckoutDir.resolve(".git")
        if (!Files.exists(gitDir)) {
            cloneFresh()
        } else {
            pullLatest()
        }
    }

    private fun cloneFresh() {
        Files.createDirectories(localCheckoutDir)
        log.info("Cloning {} (branch {}) into {}", remoteUri, branch, localCheckoutDir)
        Git
            .cloneRepository()
            .setURI(remoteUri)
            .setDirectory(localCheckoutDir.toFile())
            .setBranch(branch)
            .also { if (username != null) it.setCredentialsProvider(credentialsProvider()) }
            .call()
            .use { git ->
                lastCommitSha =
                    git.repository
                        .resolve("HEAD")
                        ?.name
                        .orEmpty()
            }
    }

    private fun pullLatest() {
        Git.open(localCheckoutDir.toFile()).use { git ->
            log.debug("Fetching latest for {} in {}", branch, localCheckoutDir)
            git
                .fetch()
                .also { if (username != null) it.setCredentialsProvider(credentialsProvider()) }
                .call()
            // Hard-reset to origin/<branch> so local edits / partial fetches never desync.
            val targetRef = "refs/remotes/origin/$branch"
            val target =
                git.repository.resolve(targetRef)
                    ?: error("Remote branch '$branch' not found in $remoteUri")
            git
                .reset()
                .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                .setRef(target.name)
                .call()
            lastCommitSha = target.name
        }
    }

    private fun workTreeRoot(): Path =
        if (subdirectory.isBlank()) localCheckoutDir else localCheckoutDir.resolve(subdirectory)

    private fun credentialsProvider(): UsernamePasswordCredentialsProvider =
        UsernamePasswordCredentialsProvider(username.orEmpty(), password.orEmpty())
}
