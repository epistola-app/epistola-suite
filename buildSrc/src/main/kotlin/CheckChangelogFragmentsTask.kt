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
import java.io.File

/**
 * Validates the changelog fragments under `changelog/unreleased/`, and checks that a change which
 * touches shipped code carries one.
 *
 * Modelled on [CheckMigrationVersionsTask]: it shells out to git for the base-ref comparison and is
 * therefore not cacheable.
 */
@DisableCachingByDefault(because = "The task inspects git history and working-tree state.")
abstract class CheckChangelogFragmentsTask : DefaultTask() {
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

    /** Set when the PR carries the `no-changelog` label, which waives the "an entry is required" check. */
    @get:Optional
    @get:Input
    abstract val skipRequirement: Property<Boolean>

    // Shipped code, deliberately excluding markdown: an area guide or a CLAUDE.md stub can live
    // under a src/main tree without being a behaviour change that a reader needs told about.
    private val shippedCode = Regex("""^(apps|modules)/[^/]+/src/main/.+|^charts/.+""")
    private val notShipped = Regex("""\.(md|txt)$""")

    @TaskAction
    fun checkChangelogFragments() {
        val root = repositoryDir.asFile.get()
        val parsed = ChangelogFragments.parseAll(File(root, "changelog/unreleased"))

        // Advisory problems (an over-long body) are reported only for fragments this change adds.
        // Warning about the whole backlog on every build is noise, and noise gets ignored.
        val addedFragments = addedFragmentNames()
        parsed.problems
            .filterNot { it.fatal }
            .filter { addedFragments.isEmpty() || it.file.name in addedFragments }
            .forEach { logger.warn("changelog: ${it.file.name}: ${it.message}") }

        val fatal = parsed.problems.filter { it.fatal }
        if (fatal.isNotEmpty()) {
            val details = fatal.joinToString("\n") { "- ${it.file.name}: ${it.message}" }
            throw GradleException(
                "Changelog fragments are malformed. See changelog/README.md for the format.\n$details",
            )
        }

        val baseRef = findBaseRef()
        if (baseRef == null) {
            logger.lifecycle("No target branch ref found; validated fragment format only.")
            return
        }
        if (skipRequirement.getOrElse(false)) {
            logger.lifecycle("no-changelog label set; validated fragment format only.")
            return
        }

        val mergeBase = runGit("merge-base", baseRef, "HEAD") ?: return
        val changed = runGit("diff", "--name-only", mergeBase, "HEAD")
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        if (changed.isEmpty()) return

        val touchesShippedCode = changed.any { shippedCode.matches(it) && !notShipped.containsMatchIn(it) }
        val addsFragment = changed.any { it.startsWith("changelog/unreleased/") && it.endsWith(".md") }

        if (touchesShippedCode && !addsFragment) {
            throw GradleException(
                """
                This change touches shipped code but adds no changelog fragment.

                Add one:
                  changelog/unreleased/${'$'}(date -u +%Y%m%d%H%M%S)-<slug>.md

                Format: changelog/README.md. If the change genuinely needs no entry, label the pull
                request `no-changelog`.
                """.trimIndent(),
            )
        }
    }

    /** Fragment filenames added since the base branch; empty when there is no base to compare with. */
    private fun addedFragmentNames(): Set<String> {
        val baseRef = findBaseRef() ?: return emptySet()
        val mergeBase = runGit("merge-base", baseRef, "HEAD") ?: return emptySet()
        return runGit("diff", "--name-only", "--diff-filter=A", mergeBase, "HEAD", "--", "changelog/unreleased")
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.map { it.substringAfterLast('/') }
            ?.toSet()
            .orEmpty()
    }

    private fun findBaseRef(): String? {
        val candidates = listOfNotNull(
            explicitBaseRef.orNull,
            envBaseRef.orNull,
            githubBaseRef.orNull?.takeIf { it.isNotBlank() }?.let { "origin/$it" },
            "origin/main",
            "main",
        )
        return candidates.firstOrNull { runGit("rev-parse", "--verify", "$it^{commit}") != null }
    }

    private fun runGit(vararg args: String): String? {
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repositoryDir.asFile.get())
            .redirectErrorStream(true)
            .start()
        val stdout = ByteArrayOutputStream()
        process.inputStream.copyTo(stdout)
        return if (process.waitFor() == 0) stdout.toString().trim() else null
    }
}
