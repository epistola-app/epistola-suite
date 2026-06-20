package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import org.thymeleaf.context.ExpressionContext
import org.thymeleaf.context.IExpressionContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.standard.expression.AssignationUtils
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.StringTemplateResolver
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Build-time gate over every `epistola-web/page-header` invocation in the
 * codebase. Plain unit test — no Spring context, no Docker — so it runs in the
 * fast `unitTest` cycle and gates every PR (same posture as [UiTestHygieneTest]).
 *
 * Why this exists: the shared header is declared as `th:fragment="page-header"`
 * with **no parameter signature** (see
 * `modules/epistola-web/src/main/resources/templates/epistola-web/page-header.html`).
 * Thymeleaf binds named arguments as local variables and parses their
 * expressions **lazily at render time**, so two whole classes of mistake are
 * invisible until a page is opened in a browser:
 *
 *  1. **Bad data** — a misspelled or missing argument silently resolves to null
 *     (`pageTitel='…'` leaves `pageTitle` null and renders an empty `<h1>`; a
 *     lone `backLinkHref` renders no link).
 *  2. **Unparseable arguments** — a bare ASCII apostrophe `'` (U+0027) or
 *     double-quote `"` (U+0022) inside an inline literal breaks Thymeleaf's
 *     fragment-call parser. The build stays green; the page **500s at runtime**.
 *     (There is no escape that survives the fragment-selector grammar — the fix
 *     is a typographic `’`/`“ ”`, or a `${...}`/`#{...}` value.)
 *
 * This test catches both. It validates the *spelling and pairing* of the
 * arguments (1), and it **parses every call site with Thymeleaf's own
 * assignation-sequence parser** — the exact parser the runtime uses for
 * fragment parameters — so a literal that would 500 fails the build instead (2).
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
        "actions",
        "stage",
    )

    /** Matches the call site `~{epistola-web/page-header :: page-header(` up to its opening paren. */
    private val callMarker = Regex("""epistola-web/page-header\s*::\s*page-header\s*\(""")

    /**
     * A parse-only Thymeleaf expression context. We never render anything — we
     * only run each call site's argument list through `AssignationUtils`, the
     * same parser Thymeleaf uses to bind `frag(name=value, …)` parameters. Built
     * from a `SpringTemplateEngine` so the dialect/parser match production (SpEL).
     */
    private val exprContext: IExpressionContext by lazy {
        val engine = SpringTemplateEngine().apply {
            setTemplateResolver(StringTemplateResolver().apply { templateMode = TemplateMode.HTML })
        }
        ExpressionContext(engine.configuration)
    }

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

        // --- bad data (spelling / pairing / presence) ---
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
            check("pageTitle='X', searchTargetId='rows'").any { "searchUrl" in it },
            "dead search parameter must be flagged",
        )

        // --- unparseable arguments (the runtime-500 class) ---
        assertTrue(
            check("pageTitle='Manage the tenant's settings'").any { "U+2019" in it },
            "a bare ASCII apostrophe in a literal must be flagged with the typographic-quote fix",
        )
        assertTrue(
            check("pageTitle=\"oops\"").any { "U+2019" in it },
            "a bare ASCII double-quote in a literal must be flagged",
        )
        // The fix works: a typographic apostrophe is accepted, the literal parses clean.
        assertTrue(
            check("pageTitle='Manage the tenant’s settings'").isEmpty(),
            "a typographic apostrophe (U+2019) must parse clean",
        )
        // Model-bound values are always fine (no inline literal to break).
        assertTrue(
            check("pageTitle=\${title}").isEmpty(),
            "a model-bound value must parse clean",
        )

        // A fully valid call — including values containing commas/parens — must pass clean.
        assertTrue(
            check(
                "pageTitle='X', pageDescription='a, b (c)', backLinkHref=@{/x}, backLinkLabel='B', " +
                    "searchUrl=@{/tenants/{t}/x/search(t=\${t})}, searchTargetId='rows', " +
                    "searchPlaceholder='Search...', actions=~{::a}, stage=\${stage}",
            ).isEmpty(),
            "a well-formed invocation must not be flagged",
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
                out += "$at — unterminated page-header(...) invocation (unbalanced parenthesis)"
                continue
            }

            // Parse the argument list with Thymeleaf's own fragment-parameter parser.
            // A bare ASCII '/" inside a literal throws here — exactly as it would 500 at runtime.
            val names: List<String> = try {
                val seq = AssignationUtils.parseAssignationSequence(
                    exprContext,
                    content.substring(open + 1, close),
                    true,
                )
                if (seq == null) {
                    out += unparseableMessage(at, "parser returned no result")
                    continue
                }
                seq.map { it.left.toString() }
            } catch (e: Exception) {
                out += unparseableMessage(at, rootCause(e).message?.replace(Regex("\\s+"), " ")?.trim() ?: "$e")
                continue
            }
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
            if ("searchUrl" in nameSet && "searchTargetId" !in nameSet) {
                out += "$at — searchUrl requires searchTargetId (the search-box hx-target would resolve to '#')"
            }
            if ("searchUrl" !in nameSet) {
                if ("searchTargetId" in nameSet) out += "$at — searchTargetId without searchUrl (dead parameter)"
                if ("searchPlaceholder" in nameSet) out += "$at — searchPlaceholder without searchUrl (dead parameter)"
            }
        }
        return count to out
    }

    /** The actionable message for an argument list Thymeleaf can't parse — tells the author exactly what to type. */
    private fun unparseableMessage(at: String, cause: String): String = buildString {
        append("$at — page-header arguments won't parse (Thymeleaf: $cause). ")
        append("A bare ASCII apostrophe ' (U+0027) or double-quote \" (U+0022) inside an inline literal breaks the ")
        append("fragment-call parser at runtime (it 500s; no escape survives the selector grammar). ")
        append("Fix: use a typographic apostrophe ’ (U+2019) or curly quotes “ ” (U+201C/U+201D) — ")
        append("e.g. pageDescription='Manage the tenant’s settings.' — or pass the value via \${...} or #{...}.")
    }

    /**
     * Index of the ')' matching the '(' at [openIdx]. Quote-insensitive on purpose: a stray ASCII
     * quote must NOT desync paren tracking (that's the very bug we're catching). Inline-literal
     * parentheses are balanced, so counting parens alone locates the close even when quotes are not.
     */
    private fun matchParen(text: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun rootCause(e: Throwable): Throwable {
        var c: Throwable = e
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return c
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
                    .toList()
            }
        }
    }
}
