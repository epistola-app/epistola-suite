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

/**
 * A bundled catalog whose content changed must also declare a newer `release.version`.
 *
 * The fingerprint is already asserted by the catalog fingerprint tests, so content cannot drift
 * away from the hash. The version is a different promise: it is what a subscriber sees and compares
 * against, and the loaders detect change by fingerprint, so nothing downstream notices when the
 * content moves and the version does not. That made the version the one part of the rule with
 * nothing enforcing it.
 */
@DisableCachingByDefault(because = "The task inspects git history and working-tree state.")
abstract class CheckBundledCatalogVersionsTask : DefaultTask() {
    // Internal, not @InputDirectory: tracking the repository root as an input makes aggregate
    // graphs fail Gradle's implicit-dependency validation against effectively every task in the
    // build. The task shells out to git, so input fingerprinting buys nothing anyway.
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

    /** The catalogs that ship inside the product. Test fixtures are deliberately frozen and excluded. */
    private val bundledManifest =
        Regex("""^(?:apps|modules)/[^/]+/src/main/resources/epistola/catalogs/(system|demo)/catalog\.json$""")

    private val releaseVersion =
        Regex(""""release"\s*:\s*\{[^}]*?"version"\s*:\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)

    @TaskAction
    fun checkBundledCatalogVersions() {
        val baseRef = findBaseRef()
        if (baseRef == null) {
            logger.lifecycle("No target branch ref found; skipped the bundled catalog version check.")
            return
        }

        val mergeBase =
            runGit("merge-base", baseRef, "HEAD")
                ?: throw GradleException("Could not determine merge-base between $baseRef and HEAD.")

        val changed =
            runGit("diff", "--name-only", mergeBase, "--", "apps", "modules")
                ?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.toList()
                .orEmpty()

        // Group every changed file by the bundled catalog directory that contains it, so a changed
        // template counts as a change to its catalog just as the manifest does.
        val touchedCatalogs =
            changed
                .mapNotNull { path -> catalogRootOf(path)?.let { it to path } }
                .groupBy({ it.first }, { it.second })

        val failures = mutableListOf<String>()

        for ((catalogRoot, files) in touchedCatalogs) {
            val manifestPath = "$catalogRoot/catalog.json"
            val committed = readFile(manifestPath)
            if (committed == null) {
                failures += "$catalogRoot changed but has no catalog.json"
                continue
            }

            val currentVersion = releaseVersion.find(committed)?.groupValues?.get(1)
            if (currentVersion == null) {
                failures += "$manifestPath has no release.version"
                continue
            }

            val baseManifest = runGit("show", "$mergeBase:$manifestPath")
            if (baseManifest == null) {
                // A new bundled catalog: nothing to compare against.
                continue
            }

            val baseVersion = releaseVersion.find(baseManifest)?.groupValues?.get(1) ?: continue
            if (isNewer(currentVersion, baseVersion)) continue

            val examples = files.take(3).joinToString("\n") { "    - $it" }
            failures +=
                "$catalogRoot changed but release.version is still $currentVersion " +
                "(it is $baseVersion on $baseRef). Bump it and regenerate release.fingerprint.\n$examples"
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                """
                A bundled catalog changed without a version bump.

                The fingerprint tests keep the hash honest, but subscribers compare versions, and the
                loaders notice content by fingerprint — so a stale version is invisible until someone
                is confused by it. See docs/catalog-versioning.md.

                ${failures.joinToString("\n")}
                """.trimIndent(),
            )
        }
    }

    private fun catalogRootOf(path: String): String? {
        val marker = "/catalog.json"
        val manifest = if (path.endsWith(marker)) path else null
        if (manifest != null && bundledManifest.matches(manifest)) {
            return manifest.removeSuffix(marker)
        }
        // Any other file inside a bundled catalog directory.
        val match = Regex("""^((?:apps|modules)/[^/]+/src/main/resources/epistola/catalogs/(?:system|demo))/.+""")
            .matchEntire(path)
        return match?.groupValues?.get(1)
    }

    /** SemVer-ish comparison; anything unparseable falls back to "it has to differ". */
    private fun isNewer(current: String, base: String): Boolean {
        val currentParts = current.split('.').mapNotNull { it.toIntOrNull() }
        val baseParts = base.split('.').mapNotNull { it.toIntOrNull() }
        if (currentParts.size != 3 || baseParts.size != 3) return current != base
        for (index in 0 until 3) {
            if (currentParts[index] != baseParts[index]) return currentParts[index] > baseParts[index]
        }
        return false
    }

    private fun readFile(path: String): String? {
        val file = repositoryDir.get().asFile.resolve(path)
        return if (file.isFile) file.readText() else null
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
