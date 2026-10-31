// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import app.epistola.suite.guards.RepoSources.repoRoot
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Build-time gate for issue #633 (UI side): every name/title input in a
 * create/edit form must carry `maxlength="100"`, and each slug input carries its
 * own min/max length bounds (the template-variant slug at `minlength="3"
 * maxlength="50"`, the font slug at `minlength="2" maxlength="64"`). Plain unit
 * test — no Spring, no Docker — so it runs in the fast `unitTest` cycle and gates
 * every PR (same posture as [IconUsageTest]).
 *
 * Why this exists: a UI `maxlength` is the only place the browser stops a user
 * before the server rejects the value. These inputs historically shipped with no
 * `maxlength` at all, so they silently let users type past the server limit. This
 * test is the independent oracle for the UI layer — the expected values (100 / 50)
 * are duplicated here on purpose so the test catches a template drifting from the
 * limit.
 *
 * Sibling oracle: `NameLengthValidationTest` (in `epistola-core`) guards the same
 * limit server-side in each command's `init {}`. Keep the two in step: an editable
 * name/title input a user can type into belongs here; a name/title command belongs
 * there. They are intentionally NOT 1:1 — entities without a UI rename (themes,
 * stencils, code lists, environments) are guarded server-side on update but only
 * appear here via their create (`new`) form.
 */
class InputMaxLengthTest {

    private data class Target(
        val template: String,
        val inputId: String,
        val requiredAttrs: List<Pair<String, String>>,
    )

    /** A name/title input that must cap at 100 characters. */
    private fun name(template: String, id: String) = Target(template, id, listOf("maxlength" to "100"))

    /** A slug input: the usual 3-character floor, with the ceiling its own entity allows. */
    private fun slug(template: String, id: String, max: Int) = Target(template, id, listOf("minlength" to "3", "maxlength" to max.toString()))

    private val base = "apps/epistola/src/main/resources/templates"

    private val targets = listOf(
        name("$base/tenants/new.html", "name"),
        name("$base/themes/new.html", "name"),
        name("$base/templates/new.html", "name"),
        name("$base/templates/detail/settings.html", "template-name-input"),
        // Stencil name caps at 255 (matches StencilHandler.createForm's maxLength(255)),
        // wider than the usual 100 — the create dialog's input mirrors that server limit.
        Target("$base/stencils/new.html", "name", listOf("maxlength" to "255")),
        name("$base/environments/new.html", "name"),
        name("$base/attributes/new.html", "displayName"),
        name("$base/attributes/list.html", "edit-attr-displayName"),
        name("$base/code-lists/new.html", "displayName"),
        name("$base/catalogs/new.html", "catalogName"),
        name("$base/templates/detail/variants.html", "title"),
        name("$base/templates/detail.html", "edit-variant-title"),
        name("$base/api-keys/new.html", "name"),
        name("$base/fonts/new.html", "name"),
        // The "template variant is different" case from #633: slug needs both bounds.
        Target(
            "$base/templates/detail/variants.html",
            "slug",
            listOf("minlength" to "3", "maxlength" to "50"),
        ),
        // Font slug carries its own bounds (2..64) — pin them like the variant slug.
        Target(
            "$base/fonts/new.html",
            "slug",
            listOf("minlength" to "2", "maxlength" to "64"),
        ),
        // Every remaining slug a user can type into. These bounds are not decoration: slug-auto.js
        // truncates a derived slug to the target field's maxLength, so a limit that drifts here
        // silently changes what gets auto-filled — and before that truncation existed, a derived
        // slug could land at five times the field's limit (themes: a 100-character name into a
        // 20-character slug).
        slug("$base/themes/new.html", "slug", 20),
        slug("$base/environments/new.html", "slug", 30),
        slug("$base/templates/new.html", "slug", 50),
        slug("$base/attributes/new.html", "slug", 50),
        slug("$base/stencils/new.html", "slug", 50),
        slug("$base/catalogs/new.html", "catalogSlug", 50),
        slug("$base/code-lists/new.html", "slug", 64),
        slug("$base/tenants/new.html", "slug", 63),
    )

    @Test
    fun `name, title and variant-slug inputs carry the expected length attributes`() {
        val violations = mutableListOf<String>()
        for (target in targets) {
            val file = repoRoot.resolve(target.template)
            if (!Files.exists(file)) {
                violations += "${target.template}: file not found"
                continue
            }
            val tag = inputTag(Files.readString(file), target.inputId)
            if (tag == null) {
                violations += "${target.template}: no <input> with id=\"${target.inputId}\""
                continue
            }
            for ((attr, value) in target.requiredAttrs) {
                if (!tag.contains("$attr=\"$value\"")) {
                    violations += "${target.template} #${target.inputId}: missing $attr=\"$value\""
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Inputs missing required length attributes (issue #633):\n" + violations.joinToString("\n"),
        )
    }

    /**
     * Every field someone types into carries a `maxlength`, or is listed below with why it has none.
     * The targets above pin the exact limits that matter most; this catches the field nobody thought of,
     * so a new one is a choice, not an omission. Matched by `id`, else `name`, per template.
     */
    private val noLimit = mapOf(
        // Filters and searches: read, never stored.
        "fragments/search.html#q" to "search box",
        "logs/list.html#log-filter-q" to "filter",
        "logs/list.html#log-filter-logger" to "filter",
        "audit/list.html#audit-filter-action" to "filter",
        "quality/list.html#q" to "filter",
        "catalogs/exchange-browse.html#exchange-search" to "search box",
        // Signing in: a limit on a password would lock people out of a long one.
        "login.html#username" to "login",
        "login.html#password" to "login",
        // Stored without a limit (TEXT columns, no limit in the command).
        "attributes/new.html#allowedValues" to "no server limit",
        "attributes/list.html#edit-attr-allowedValues" to "no server limit",
        "catalogs/list.html#release-notes" to "no server limit",
        "catalogs/metadata.html#catalog-metadata-description" to "no server limit",
        "catalogs/metadata.html#catalog-license-name" to "no server limit",
        "catalogs/metadata.html#catalog-license-spdx" to "no server limit",
        "catalogs/metadata.html#catalog-license-url" to "no server limit",
        "catalogs/metadata.html#catalog-license-copyright" to "no server limit",
        "catalogs/metadata.html#\${'catalog-attribute-' + field.descriptor.qualifiedKey}" to "no server limit (an attribute value)",
        "catalogs/subscribe.html#authCredential" to "a credential, stored encrypted",
        "themes/new.html#description" to "no server limit",
        "stencils/new.html#tags" to "a list; each tag is limited (50) on the server",
        "loadtest/new.html#testData" to "JSON test data",
        "quality/manual-form.html#manual-message" to "no server limit",
        "feedback/detail.html#body" to "no server limit",
        "feedback/submit-form.html#fb-title" to "no server limit",
        "feedback/submit-form.html#fb-description" to "no server limit",
    )

    @Test
    fun `every text field has a maxlength or a stated reason not to`() {
        val textTypes = setOf("text", "search", "email", "url", "tel", "password")
        val violations = RepoSources.uiAssetFiles().filter { it.toString().endsWith(".html") }.flatMap { path ->
            val relative = RepoSources.relativize(path).substringAfter("/templates/")
            val html = RepoSources.stripHtmlComments(Files.readString(path))
            Regex("""<(input|textarea)\b[^>]*>""").findAll(html).mapNotNull { match ->
                val tag = match.value
                val type = Regex("""\btype="([^"]+)"""").find(tag)?.groupValues?.get(1)
                val typed = match.groupValues[1] == "textarea" || type == null || type in textTypes
                val named = Regex("""\b(?:id|name)="([^"]+)"""").find(tag)?.groupValues?.get(1).orEmpty()
                val exempt = "$relative#$named" in noLimit
                if (!typed || exempt || Regex("""\b(th:)?maxlength=""").containsMatchIn(tag) || "readonly" in tag) null else "$relative: <${match.groupValues[1]} $named>"
            }.toList()
        }
        assertTrue(violations.isEmpty(), "Text fields without a maxlength (add the server's limit, or list it in noLimit with why):\n" + violations.joinToString("\n"))
    }

    /**
     * Issue #608 (UI side): the code-list entry rows are built by JS (dynamic ids),
     * so they can't be matched by id like the targets above. Since the strict-CSP
     * migration (ADR 0010) the row markup lives in the static JS file rather than an
     * inline `<script>`, so read it there. Match the inputs by their `aria-label` and
     * pin the `maxlength` that mirrors the DB columns (`code VARCHAR(64)`,
     * `label VARCHAR(200)`), the browser-side counterpart to the server-side
     * `CodeListEntryLengthValidationTest`.
     */
    @Test
    fun `code-list entry inputs cap at the DB column lengths`() {
        val js = Files.readString(repoRoot.resolve("apps/epistola/src/main/resources/static/js/pages/asset-forms.js"))
        val codeTag = inputTagByAria(js, "Entry code")
        val labelTag = inputTagByAria(js, "Entry label")
        assertTrue(codeTag != null && codeTag.contains("""maxlength="64""""), "entry code input must carry maxlength=\"64\": $codeTag")
        assertTrue(labelTag != null && labelTag.contains("""maxlength="200""""), "entry label input must carry maxlength=\"200\": $labelTag")
    }

    /** Returns the full `<input …>` tag whose attributes include `aria-label="<label>"`, or null. */
    private fun inputTagByAria(html: String, ariaLabel: String): String? = Regex("""<input\b[^>]*\baria-label="${Regex.escape(ariaLabel)}"[^>]*>""").find(html)?.value

    /**
     * Negative self-test: proves the tag matcher actually finds an input and reads
     * its attributes (and returns null for a missing id), so the main test cannot
     * pass on a matcher that silently matches nothing.
     */
    @Test
    fun `input tag matcher extracts the tag and is not fooled`() {
        val sample = """
            <div>
              <input type="text" id="name" name="name" class="ep-input"
                     required maxlength="100" placeholder="x">
            </div>
        """.trimIndent()

        val tag = inputTag(sample, "name")
        assertTrue(tag != null && tag.contains("""maxlength="100""""), "matcher should find the tag and its maxlength")
        assertEquals(null, inputTag(sample, "missing"), "matcher should return null for an absent id")
    }

    /** Returns the full `<input …>` tag whose attributes include `id="<id>"`, or null. */
    private fun inputTag(html: String, id: String): String? = Regex("""<input\b[^>]*\bid="${Regex.escape(id)}"[^>]*>""").find(html)?.value
}
