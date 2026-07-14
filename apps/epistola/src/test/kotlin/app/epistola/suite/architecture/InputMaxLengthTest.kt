package app.epistola.suite.architecture

import app.epistola.suite.architecture.RepoSources.repoRoot
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

    private val base = "apps/epistola/src/main/resources/templates"

    private val targets = listOf(
        name("$base/tenants/list.html", "name"),
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
        name("$base/catalogs/list.html", "catalogName"),
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
