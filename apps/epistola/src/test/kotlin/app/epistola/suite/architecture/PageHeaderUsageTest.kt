package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Build-time gate over every `epistola-web/page-header` invocation in the
 * codebase. Plain unit test — no Spring, no Docker — so it runs in the fast
 * `unitTest` cycle and gates every PR (same posture as [UiTestHygieneTest]).
 *
 * Why this exists: the shared header is declared as `th:fragment="page-header"`
 * with **no parameter signature** (see
 * `modules/epistola-web/src/main/resources/templates/epistola-web/page-header.html`).
 * Thymeleaf binds named arguments as local variables, so a misspelled or
 * missing argument is **never** an error — it silently resolves to null. A
 * typo'd `pageTitel='…'` leaves `pageTitle` null and the page renders an empty
 * `<h1>`; a lone `backLinkHref` (without `backLinkLabel`) renders no link at
 * all. Neither the compiler nor the template engine catches it, and no
 * render-time test asserts header structure. This static check is the only
 * thing that does.
 *
 * It validates the *data* each call site passes (not that the header renders) —
 * the spelling and well-formedness of the arguments, across all 40-plus call
 * sites in the host app and the feature modules.
 */
class PageHeaderUsageTest {

    /** The contract — keep in lockstep with the fragment's `th:with`/markup. */
    private val allowedParams = setOf(
        "pageTitle",
        "pageDescription",
        "backLinkHref",
        "backLinkLabel",
        "searchPlaceholder",
        "searchUrl",
        "searchTargetId",
        "searchFormDriven",
        "searchValue",
        "actions",
        "stage",
    )

    /** Matches the call site `~{epistola-web/page-header :: page-header(` up to its opening paren. */
    private val callMarker = Regex("""epistola-web/page-header\s*::\s*page-header\s*\(""")

    @Test
    fun `every page-header invocation passes valid, well-formed parameters`() {
        val roots = templateRoots()
        assertTrue(
            roots.isNotEmpty(),
            "Found no template roots under $repoRoot — the scanner would pass vacuously; fix the path.",
        )

        val violations = mutableListOf<String>()
        var callCount = 0
        for (root in roots) {
            Files.walk(root).use { paths ->
                paths
                    .filter { it.toString().endsWith(".html") }
                    .forEach { path: Path ->
                        val label = repoRoot.relativize(path).toString()
                        val (calls, found) = validate(label, Files.readString(path))
                        violations += found
                        callCount += calls
                    }
            }
        }

        // The header is used on ~40 pages; if the scanner suddenly sees almost
        // none, the marker/parser has broken and the test would pass for the
        // wrong reason. Fail loudly instead of going green on an empty scan.
        assertTrue(
            callCount >= 30,
            "Only matched $callCount page-header call sites — expected 30+. " +
                "The call marker or parser likely regressed; this test must not pass on an empty scan.",
        )

        assertTrue(
            violations.isEmpty(),
            "Invalid page-header invocations (contract: " +
                "modules/epistola-web/src/main/resources/templates/epistola-web/page-header.html):\n\n" +
                violations.joinToString("\n"),
        )
    }

    /** Negative self-test: proves the validator rejects malformed invocations (not just that prod is clean). */
    @Test
    fun `validator rejects malformed page-header invocations`() {
        fun check(args: String): List<String> = validate(
            "fixture.html",
            """<div th:replace="~{epistola-web/page-header :: page-header($args)}"></div>""",
        ).second

        assertTrue(check("pageTitel='X'").any { "unknown" in it }, "typo'd parameter must be flagged")
        assertTrue(check("pageDescription='x'").any { "pageTitle" in it }, "missing pageTitle must be flagged")
        assertTrue(check("pageTitle='X', pageTitle='Y'").any { "duplicate" in it }, "duplicate must be flagged")
        assertTrue(
            check("pageTitle='X', backLinkHref=@{/x}").any { "backLink" in it },
            "lone backLinkHref must be flagged",
        )
        assertTrue(
            check("pageTitle='X', backLinkLabel='B'").any { "backLink" in it },
            "lone backLinkLabel must be flagged",
        )
        assertTrue(
            check("pageTitle='X', searchUrl=@{/s}").any { "searchTargetId" in it },
            "searchUrl without searchTargetId must be flagged",
        )
        assertTrue(
            check("pageTitle='X', searchTargetId='rows'").any { "searchTargetId" in it },
            "dead searchTargetId (no searchUrl) must be flagged",
        )
        assertTrue(
            check("pageTitle='X', searchPlaceholder='Search...'").any { "searchPlaceholder" in it },
            "searchPlaceholder without searchUrl or searchFormDriven must be flagged",
        )
        // A standalone search call — including values containing commas/parens — must pass clean.
        assertTrue(
            check(
                "pageTitle='X', pageDescription='a, b (c)', backLinkHref=@{/x}, backLinkLabel='B', " +
                    "searchUrl=@{/tenants/{t}/x/search(t=\${t})}, searchTargetId='rows', " +
                    "searchPlaceholder='Search...', actions=~{::a}, stage=\${stage}",
            ).isEmpty(),
            "a well-formed standalone-search invocation must not be flagged",
        )
        // A form-driven search call (no searchUrl/searchTargetId) must also pass clean.
        assertTrue(
            check("pageTitle='X', searchPlaceholder='Search...', searchFormDriven=true, searchValue=\${query.q}, actions=~{::a}").isEmpty(),
            "a well-formed form-driven-search invocation must not be flagged",
        )
        assertTrue(
            check("pageTitle='X', searchValue='foo'").any { "searchValue" in it },
            "searchValue without searchUrl or searchFormDriven must be flagged",
        )
    }

    /**
     * Validates every page-header call site in [content].
     * Returns (number of call sites seen, list of violation messages).
     */
    private fun validate(label: String, content: String): Pair<Int, List<String>> {
        val out = mutableListOf<String>()
        var count = 0
        for (m in callMarker.findAll(content)) {
            count++
            val open = m.range.last // the marker ends on '('
            val close = matchParen(content, open)
            val line = content.substring(0, m.range.first).count { it == '\n' } + 1
            val at = "$label:$line"
            if (close < 0) {
                out += "$at — unterminated page-header(...) invocation"
                continue
            }
            val names = splitTopArgs(content.substring(open + 1, close)).map { argName(it) }
            val nameSet = names.toSet()

            names.filter { it !in allowedParams }.toSet().forEach {
                out += "$at — unknown parameter '$it' (allowed: ${allowedParams.sorted().joinToString(", ")})"
            }
            val seen = mutableSetOf<String>()
            names.filter { !seen.add(it) }.toSet().forEach {
                out += "$at — duplicate parameter '$it'"
            }
            if ("pageTitle" !in nameSet) {
                out += "$at — missing required parameter 'pageTitle'"
            }
            if (("backLinkHref" in nameSet) != ("backLinkLabel" in nameSet)) {
                out += "$at — backLinkHref and backLinkLabel must be provided together (a lone one renders no back-link)"
            }
            // Search is enabled either by a standalone searchUrl (the input drives its own
            // request) or by searchFormDriven=true (a plain field driven by an enclosing form).
            val searchEnabled = "searchUrl" in nameSet || "searchFormDriven" in nameSet
            if ("searchUrl" in nameSet && "searchTargetId" !in nameSet) {
                out += "$at — searchUrl requires searchTargetId (the search-box hx-target would resolve to '#')"
            }
            if ("searchUrl" !in nameSet && "searchTargetId" in nameSet) {
                out += "$at — searchTargetId without searchUrl (dead parameter; form-driven search needs no target)"
            }
            if (!searchEnabled && "searchPlaceholder" in nameSet) {
                out += "$at — searchPlaceholder without searchUrl or searchFormDriven (dead parameter)"
            }
            if (!searchEnabled && "searchValue" in nameSet) {
                out += "$at — searchValue without searchUrl or searchFormDriven (dead parameter)"
            }
        }
        return count to out
    }

    /** Index of the ')' matching the '(' at [openIdx], honoring single-quoted Thymeleaf string literals. */
    private fun matchParen(text: String, openIdx: Int): Int {
        var depth = 0
        var inQuote = false
        var i = openIdx
        while (i < text.length) {
            val ch = text[i]
            when {
                inQuote -> if (ch == '\'') inQuote = false
                ch == '\'' -> inQuote = true
                ch == '(' -> depth++
                ch == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Splits an argument list on top-level commas — values may contain nested commas/parens/braces. */
    private fun splitTopArgs(s: String): List<String> {
        val args = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var inQuote = false
        for (ch in s) {
            when {
                inQuote -> {
                    cur.append(ch)
                    if (ch == '\'') inQuote = false
                }
                ch == '\'' -> {
                    inQuote = true
                    cur.append(ch)
                }
                ch == '(' || ch == '{' || ch == '[' -> {
                    depth++
                    cur.append(ch)
                }
                ch == ')' || ch == '}' || ch == ']' -> {
                    depth--
                    cur.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    args.add(cur.toString())
                    cur.setLength(0)
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotBlank()) args.add(cur.toString())
        return args.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** The identifier before the top-level '=' of a single argument (e.g. `searchUrl=@{…}` -> `searchUrl`). */
    private fun argName(arg: String): String {
        var depth = 0
        var inQuote = false
        for (i in arg.indices) {
            val ch = arg[i]
            when {
                inQuote -> if (ch == '\'') inQuote = false
                ch == '\'' -> inQuote = true
                ch == '(' || ch == '{' || ch == '[' -> depth++
                ch == ')' || ch == '}' || ch == ']' -> depth--
                ch == '=' && depth == 0 -> return arg.substring(0, i).trim()
            }
        }
        return arg.trim()
    }

    private val repoRoot: Path = run {
        var dir = Paths.get("").toAbsolutePath()
        while (dir.parent != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent
        }
        dir
    }

    /** Every `<app-or-module>/src/main/resources/templates` root — the host app and every feature module. */
    private fun templateRoots(): List<Path> = listOf("apps", "modules").flatMap { top ->
        val base = repoRoot.resolve(top)
        if (!Files.isDirectory(base)) {
            emptyList()
        } else {
            Files.newDirectoryStream(base).use { stream ->
                stream
                    .map { it.resolve("src/main/resources/templates") }
                    .filter { Files.isDirectory(it) }
            }
        }
    }
}
