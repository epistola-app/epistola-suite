// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.validation

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.commands.ImportAsset
import app.epistola.suite.catalog.commands.ImportAttribute
import app.epistola.suite.catalog.commands.ImportCodeList
import app.epistola.suite.catalog.commands.ImportStencil
import app.epistola.suite.catalog.commands.ImportTemplateInput
import app.epistola.suite.catalog.commands.ImportTemplates
import app.epistola.suite.catalog.commands.ImportTheme
import app.epistola.suite.catalog.commands.ImportVariantInput
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.validation.FieldLimits.MAX_DISPLAY_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.FieldLimits.MAX_NAME_COLUMN_LENGTH
import app.epistola.template.model.ThemeRef
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Cross-layer oracle for #692 (server side): the non-interactive IMPORT write
 * paths must reject a name/title/display-name that overflows its DB column
 * (`VARCHAR(255)` for names/titles, `VARCHAR(100)` for display-names) at command
 * construction, so over-length catalog/remote/restore data never reaches the DB
 * as a SQLSTATE 22001 (which surfaces as an opaque 500).
 *
 * Crucially, imports validate at the **column width**, NOT the tighter
 * interactive [FieldLimits.MAX_NAME_LENGTH] (100) enforced by
 * [NameLengthValidationTest]. A value in the 101–255 range imports fine — that is
 * the backwards-compatibility guarantee: content that fits the column today keeps
 * importing after this fix. Applying the UX cap here would reject existing valid
 * catalogs and abort snapshot restores.
 *
 * The expected values (255 / 100) are duplicated on purpose — this is the
 * independent oracle, so it catches a command drifting from the limit AND an
 * accidental change to the [FieldLimits] constants.
 */
class ImportNameLengthValidationTest {

    private val tenantId = TenantId(TenantKey("testtenant"))
    private val catalogKey = CatalogKey.DEFAULT

    private fun content(): TemplateDocument = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("root-slot"))),
        slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children")),
        themeRef = ThemeRef.Inherit,
    )

    private fun variantInput(title: String?) = ImportVariantInput(id = "english", title = title, attributes = emptyMap(), templateModel = null)

    private fun templateInput(name: String, variants: List<ImportVariantInput> = listOf(variantInput("English"))) = ImportTemplateInput(
        slug = "invoice",
        name = name,
        version = "1",
        dataModel = null,
        dataExamples = emptyList(),
        templateModel = content(),
        variants = variants,
        publishTo = emptyList(),
    )

    /** Commands whose name/title column is VARCHAR(255). label -> builder taking the field value. */
    private val name255Cases: List<Pair<String, (String) -> Any>> = listOf(
        "ImportTheme.name" to { s -> ImportTheme(tenantId, catalogKey, slug = "mytheme", name = s) },
        "ImportStencil.name" to { s -> ImportStencil(tenantId, catalogKey, slug = "mystencil", version = 1, name = s, content = content()) },
        "ImportAsset.name" to { s -> ImportAsset(tenantId, catalogKey, id = AssetKey.generate(), name = s, mediaType = AssetMediaType.fromMimeType("image/png"), content = ByteArray(1)) },
        "UploadAsset.name" to { s -> UploadAsset(tenantId.key, name = s, mediaType = AssetMediaType.fromMimeType("image/png"), content = ByteArray(1), width = null, height = null, catalogKey = catalogKey) },
        "ImportTemplates.name" to { s -> ImportTemplates(tenantId, catalogKey, templates = listOf(templateInput(name = s))) },
        // Resolved variant title (a blank title falls back to the id, so a non-blank over-length title is the failure mode).
        "ImportTemplates.variant.title" to { s -> ImportTemplates(tenantId, catalogKey, templates = listOf(templateInput(name = "Invoice", variants = listOf(variantInput(title = s))))) },
    )

    /** Commands whose display_name column is VARCHAR(100). */
    private val displayName100Cases: List<Pair<String, (String) -> Any>> = listOf(
        "ImportAttribute.displayName" to { s -> ImportAttribute(tenantId, catalogKey, slug = "myattr", displayName = s) },
        "ImportCodeList.displayName" to { s -> ImportCodeList(tenantId, catalogKey, slug = "mycodelist", displayName = s) },
    )

    @Test
    fun `the canonical import column limits match the DB columns`() {
        assertEquals(255, MAX_NAME_COLUMN_LENGTH)
        assertEquals(100, MAX_DISPLAY_NAME_COLUMN_LENGTH)
    }

    @Test
    fun `import name and title fields reject input longer than the 255 column`() {
        assertAllReject(name255Cases, "x".repeat(256))
    }

    @Test
    fun `import display-name fields reject input longer than the 100 column`() {
        assertAllReject(displayName100Cases, "x".repeat(101))
    }

    @Test
    fun `import name and title fields accept input at exactly the 255 column`() {
        assertAllAccept(name255Cases, "x".repeat(255))
    }

    @Test
    fun `import display-name fields accept input at exactly the 100 column`() {
        assertAllAccept(displayName100Cases, "x".repeat(100))
    }

    @Test
    fun `import name fields accept values above the interactive limit but within the column (backwards compat)`() {
        // The core #692 guarantee: 101–255 char names — legal for the column, over the
        // UX cap — must still import. Rejecting these would break existing catalogs.
        assertAllAccept(name255Cases, "x".repeat(200))
    }

    private fun assertAllReject(cases: List<Pair<String, (String) -> Any>>, value: String) {
        val violations = cases.mapNotNull { (label, build) ->
            when (val thrown = runCatching { build(value) }.exceptionOrNull()) {
                // Require the max-length failure specifically (field + message), so an
                // unrelated ValidationException can't mask a missing length check.
                is ValidationException ->
                    if (thrown.field == label.substringAfterLast('.') && thrown.message.contains("characters or less")) {
                        null
                    } else {
                        "$label: rejected for the wrong reason (field '${thrown.field}': ${thrown.message})"
                    }
                null -> "$label: accepted a ${value.length}-char value (no length validation)"
                else -> "$label: threw ${thrown::class.simpleName} instead of ValidationException"
            }
        }
        if (violations.isNotEmpty()) {
            fail("Import length validation missing or wrong (issue #692):\n" + violations.joinToString("\n"))
        }
    }

    private fun assertAllAccept(cases: List<Pair<String, (String) -> Any>>, value: String) {
        for ((label, build) in cases) {
            val thrown = runCatching { build(value) }.exceptionOrNull()
            if (thrown is ValidationException) {
                fail("$label: rejected a ${value.length}-char value: ${thrown.message}")
            }
        }
    }
}
