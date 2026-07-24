// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream

@DisableCachingByDefault(because = "The task inspects git history and working-tree state.")
abstract class CheckMigrationVersionsTask : DefaultTask() {
    @get:InputDirectory
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

    private fun migrationVersion(path: String): Long? =
        runtimeMigrationPath.matchEntire(path)?.groupValues?.get(1)?.toLong()

    private fun runGit(vararg args: String): String? {
        val process =
            ProcessBuilder(listOf("git", *args))
                .directory(repositoryDir.asFile.get())
                .redirectErrorStream(true)
                .start()
        val stdout = ByteArrayOutputStream()
        process.inputStream.copyTo(stdout)
        return if (process.waitFor() == 0) stdout.toString().trim() else null
    }
}
