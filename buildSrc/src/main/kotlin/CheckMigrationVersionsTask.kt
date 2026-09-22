// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

@DisableCachingByDefault(because = "The task inspects git history and working-tree state.")
abstract class CheckMigrationVersionsTask : DefaultTask() {
    // Internal, not @InputDirectory: the repository root contains every other
    // task's outputs (build/, buildSrc/build/, ...), so tracking it as an input
    // makes aggregate graphs (root `build`/`check`) fail Gradle's
    // implicit-dependency validation against effectively every task in the
    // build. The task is @DisableCachingByDefault and shells out to git, so
    // input fingerprinting of the tree buys nothing anyway.
    @get:Internal
    abstract val repositoryDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val explicitBaseRef: Property<String>

    @get:Optional
    @get:Input
    abstract val envBaseRef: Property<String>

    @get:Optional
    @get:Input
    abstract val githubBaseRef: Property<String>

    private val runtimeMigrationPath =
        Regex("""^modules/[^/]+/src/main/resources/db/migration/[^/]+/V(\d{14})__.+\.sql$""")

    /**
     * A merged migration someone deliberately edited, pinned to the exact content that was reviewed.
     * Only a file that no release has shipped belongs here: an installation that already applied it
     * fails Flyway's checksum validation, so the edit is safe only where the sole databases affected
     * are disposable development ones. Anything but these bytes fails the check again, so the
     * exemption covers one reviewed edit, not the file.
     *
     * Reproduce a digest with `shasum -a 256 <path>`.
     */
    private data class ReviewedModification(val sha256: String, val reason: String)

    private val reviewedModifications =
        mapOf(
            "modules/epistola-core/src/main/resources/db/migration/core/V20260905090000__core_catalog_resource_identity.sql" to
                ReviewedModification(
                    sha256 = "e58967608215928214057bcaea736d8a63eba29c0941b4df344954fcca921482",
                    reason =
                        "Unreleased when edited. Replaced PostgreSQL 18's uuidv7() with epistola_uuidv7() " +
                            "so the release keeps supporting PostgreSQL 17, then removed the relocation " +
                            "alias table and the constraint that existed only as its foreign-key target: " +
                            "relocation became a move that leaves no aliases before any release shipped them.",
                ),
            "modules/epistola-core/src/main/resources/db/migration/core/V20260920160936__core_catalog_resource_type_image.sql" to
                ReviewedModification(
                    sha256 = "bea35a45fe9b4a0445bc94b989a321c99bef6e36e495513f8965ee8ed61bf627",
                    reason =
                        "Unreleased when edited. Dropped the retyping of catalog_resource_aliases rows, " +
                            "a table removed from V20260905090000 before any release shipped it.",
                ),
        )

    @TaskAction
    fun checkMigrationVersions() {
        val currentMigrationPaths =
            runGit("ls-files", "--cached", "--others", "--exclude-standard", "--", "modules")
                ?.lineSequence()
                ?.filter { migrationVersion(it) != null }
                ?.toList()
                .orEmpty()

        val duplicateVersions =
            currentMigrationPaths
                .groupBy { requireNotNull(migrationVersion(it)) }
                .filterValues { it.size > 1 }

        if (duplicateVersions.isNotEmpty()) {
            val details =
                duplicateVersions.entries.joinToString("\n") { (version, paths) ->
                    "V$version is used by:\n${paths.joinToString("\n") { "  - $it" }}"
                }
            throw GradleException("Runtime Flyway migration versions must be globally unique.\n$details")
        }

        val baseRef = findBaseRef()
        if (baseRef == null) {
            logger.lifecycle("No target branch ref found; checked current migration version uniqueness only.")
            return
        }

        val mergeBase =
            runGit("merge-base", baseRef, "HEAD")
                ?: throw GradleException("Could not determine merge-base between $baseRef and HEAD.")

        val targetMigrationPaths =
            runGit("ls-tree", "-r", "--name-only", baseRef, "--", "modules")
                ?.lineSequence()
                ?.filter { migrationVersion(it) != null }
                ?.toSet()
                .orEmpty()

        val targetMaxVersion =
            targetMigrationPaths
                .asSequence()
                .mapNotNull { migrationVersion(it) }
                .maxOrNull()

        val failures = mutableListOf<String>()
        if (targetMaxVersion != null) {
            currentMigrationPaths
                .filterNot { it in targetMigrationPaths }
                .forEach { path ->
                    val version = requireNotNull(migrationVersion(path))
                    if (version <= targetMaxVersion) {
                        failures +=
                            "New runtime migration $path has version V$version, " +
                            "but $baseRef already contains runtime migration V$targetMaxVersion. " +
                            "Generate a fresh timestamp after the previously committed migration history."
                    }
                }
        }

        val changedLines =
            runGit("diff", "--name-status", mergeBase, "HEAD", "--", "modules")
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                .orEmpty()

        for (line in changedLines) {
            val fields = line.split('\t')
            if (fields.isEmpty()) continue

            val status = fields[0].first()
            val oldPath = fields.getOrNull(1)
            val newPath = if (status == 'R' || status == 'C') fields.getOrNull(2) else oldPath
            val oldIsMigration = oldPath?.let { migrationVersion(it) != null } == true
            val newVersion = newPath?.let { migrationVersion(it) }

            when {
                status == 'M' && oldIsMigration && isReviewedModification(requireNotNull(oldPath)) ->
                    logger.lifecycle(
                        "Merged runtime migration was modified as reviewed: $oldPath " +
                            "(${reviewedModifications.getValue(oldPath).reason})",
                    )
                status == 'M' && oldIsMigration ->
                    failures += "Merged runtime migration was modified: $oldPath"
                status == 'D' && oldIsMigration ->
                    failures += "Merged runtime migration was deleted: $oldPath"
                status == 'R' && (oldIsMigration || newVersion != null) ->
                    failures += "Runtime migrations must not be renamed: $oldPath -> $newPath"
            }
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                """
                Runtime Flyway migrations must be append-only relative to $baseRef.
                ${failures.joinToString("\n") { "- $it" }}
                """.trimIndent(),
            )
        }
    }

    private fun findBaseRef(): String? {
        val candidateBaseRefs =
            listOfNotNull(
                explicitBaseRef.orNull,
                envBaseRef.orNull,
                githubBaseRef.orNull?.takeIf { it.isNotBlank() }?.let { "origin/$it" },
                "origin/main",
                "main",
            )

        return candidateBaseRefs.firstOrNull { ref ->
            runGit("rev-parse", "--verify", "$ref^{commit}") != null
        }
    }

    private fun isReviewedModification(path: String): Boolean {
        val reviewed = reviewedModifications[path] ?: return false
        val content = gitBytes("show", "HEAD:$path") ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        if (digest != reviewed.sha256) {
            logger.error("$path is exempt only at sha256 ${reviewed.sha256}; HEAD has $digest.")
            return false
        }
        return true
    }

    private fun migrationVersion(path: String): Long? =
        runtimeMigrationPath.matchEntire(path)?.groupValues?.get(1)?.toLong()

    private fun runGit(vararg args: String): String? = gitBytes(*args)?.toString(Charsets.UTF_8)?.trim()

    private fun gitBytes(vararg args: String): ByteArray? {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(repositoryDir.asFile.get())
                .redirectErrorStream(true)
                .start()
        val stdout = ByteArrayOutputStream()
        process.inputStream.copyTo(stdout)
        return if (process.waitFor() == 0) stdout.toByteArray() else null
    }
}
