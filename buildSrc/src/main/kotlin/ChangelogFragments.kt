// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

import java.io.File

/**
 * Parsing, validation and rendering for changelog fragments — one file per change under
 * `changelog/unreleased/`, assembled into `CHANGELOG.md` at release time.
 *
 * The frontmatter is a deliberately small, flat YAML subset (scalars and inline lists) so this
 * needs no YAML dependency in buildSrc, and so a fragment stays readable in a diff.
 */
object ChangelogFragments {
    /** Conventional-commit types, in the order the in-app dialog groups them. */
    val TYPES = listOf("feat", "fix", "perf", "refactor", "docs", "test", "build", "ci", "chore")

    private val AUDIENCES = setOf("user", "dev")
    private val MATURITIES = setOf("alpha", "beta")
    private val SCOPE = Regex("""^[a-z0-9][a-z0-9.-]*$""")
    private val FILENAME = Regex("""^\d{14}-[a-z0-9][a-z0-9-]*\.md$""")

    /** Bodies longer than this are allowed but reported: the in-app reader wants a paragraph. */
    const val BODY_WARN_CHARS = 900

    data class Fragment(
        val file: File,
        val type: String,
        val scopes: List<String>,
        val title: String,
        val audience: String?,
        val breaking: Boolean,
        val maturity: String?,
        val issues: List<Int>,
        val body: String,
    ) {
        /** The timestamp in the filename, which orders fragments within a release. */
        val timestamp: String get() = file.name.substringBefore('-')

        /**
         * Renders the entry in the format `ChangelogRenderer` already parses:
         * `- [**[audience]** ]type(scope)[!]: **Title.** body`
         */
        fun render(): String = buildString {
            append("- ")
            audience?.let { append("**[$it]** ") }
            append(type)
            append('(').append(scopes.joinToString(",")).append(')')
            if (breaking) append('!')
            append(": **").append(title).append("**")
            if (body.isNotBlank()) append(' ').append(body.replace("\n", " ").trim())
        }
    }

    /** A problem with one fragment. [fatal] false means it is reported but does not fail the build. */
    data class Problem(val file: File, val message: String, val fatal: Boolean = true)

    class Parsed(val fragments: List<Fragment>, val problems: List<Problem>)

    fun parseAll(directory: File): Parsed {
        if (!directory.isDirectory) return Parsed(emptyList(), emptyList())
        val fragments = mutableListOf<Fragment>()
        val problems = mutableListOf<Problem>()
        directory.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") && it.name != "README.md" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                when (val result = parse(file)) {
                    is Result.Ok -> {
                        fragments += result.fragment
                        problems += result.warnings
                    }
                    is Result.Failed -> problems += result.problems
                }
            }
        return Parsed(fragments, problems)
    }

    private sealed interface Result {
        class Ok(val fragment: Fragment, val warnings: List<Problem>) : Result
        class Failed(val problems: List<Problem>) : Result
    }

    private fun parse(file: File): Result {
        val problems = mutableListOf<Problem>()
        fun fail(message: String): Result.Failed = Result.Failed(problems + Problem(file, message))

        if (!FILENAME.matches(file.name)) {
            problems += Problem(file, "Name must be <14-digit UTC timestamp>-<kebab-slug>.md, as `date -u +%Y%m%d%H%M%S` produces.")
        }

        val text = file.readText()
        if (!text.startsWith("---")) return fail("Must start with `---` frontmatter.")
        val end = text.indexOf("\n---", startIndex = 3)
        if (end < 0) return fail("Frontmatter is not closed by a `---` line.")

        val frontmatter = text.substring(3, end).trim('\n', '\r')
        val body = text.substring(end + 4).trim()

        val fields = mutableMapOf<String, String>()
        for (rawLine in frontmatter.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val separator = line.indexOf(':')
            if (separator <= 0) {
                problems += Problem(file, "Frontmatter line is not `key: value`: $line")
                continue
            }
            fields[line.substring(0, separator).trim()] = line.substring(separator + 1).trim()
        }

        val unknown = fields.keys - setOf("type", "scopes", "title", "audience", "breaking", "maturity", "issues")
        if (unknown.isNotEmpty()) problems += Problem(file, "Unknown field(s): ${unknown.joinToString()}.")

        val type = fields["type"]
        if (type == null) {
            problems += Problem(file, "`type` is required.")
        } else if (type !in TYPES) {
            problems += Problem(file, "`type` must be one of ${TYPES.joinToString()} but was '$type'.")
        }

        val scopes = fields["scopes"].orEmpty().trim('[', ']').split(',').map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        if (scopes.isEmpty()) {
            problems += Problem(file, "`scopes` is required, e.g. `scopes: [catalog]`.")
        } else {
            scopes.filterNot { SCOPE.matches(it) }.forEach {
                problems += Problem(file, "Scope '$it' must be lowercase kebab-case.")
            }
        }

        val title = fields["title"]?.trim('"', '\'').orEmpty()
        if (title.isBlank()) problems += Problem(file, "`title` is required.")
        if (title.contains("**")) problems += Problem(file, "`title` is emphasised when rendered; drop the `**`.")

        val audience = fields["audience"]?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
        if (audience != null && audience !in AUDIENCES) {
            problems += Problem(file, "`audience` must be `user` or `dev` (omit it for everyone) but was '$audience'.")
        }

        val maturity = fields["maturity"]?.trim('"', '\'')?.takeIf { it.isNotEmpty() }
        if (maturity != null && maturity !in MATURITIES) {
            problems += Problem(file, "`maturity` must be `alpha` or `beta` but was '$maturity'.")
        }

        val breakingRaw = fields["breaking"]
        if (breakingRaw != null && breakingRaw !in setOf("true", "false")) {
            problems += Problem(file, "`breaking` must be true or false but was '$breakingRaw'.")
        }

        val issues = fields["issues"].orEmpty().trim('[', ']').split(',')
            .mapNotNull { it.trim().removePrefix("#").toIntOrNull() }

        if (body.isBlank()) problems += Problem(file, "The body paragraph is required.")

        if (problems.any { it.fatal }) return Result.Failed(problems)

        val warnings = mutableListOf<Problem>()
        if (body.length > BODY_WARN_CHARS) {
            warnings += Problem(file, "Body is ${body.length} characters; aim for under $BODY_WARN_CHARS.", fatal = false)
        }

        return Result.Ok(
            Fragment(
                file = file,
                type = type!!,
                scopes = scopes,
                title = title,
                audience = audience,
                breaking = breakingRaw == "true",
                maturity = maturity,
                issues = issues,
                body = body,
            ),
            warnings,
        )
    }

    /** Release order: breaking first, then by the dialog's type order, then scope, then timestamp. */
    fun sorted(fragments: List<Fragment>): List<Fragment> = fragments.sortedWith(
        compareByDescending<Fragment> { it.breaking }
            .thenBy { TYPES.indexOf(it.type).let { index -> if (index < 0) TYPES.size else index } }
            .thenBy { it.scopes.firstOrNull().orEmpty() }
            .thenBy { it.timestamp },
    )

    /** Renders fragments as the body of a changelog section, one blank line between entries. */
    fun renderSection(fragments: List<Fragment>): String =
        sorted(fragments).joinToString("\n\n") { it.render() }
}
