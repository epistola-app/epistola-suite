// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.validation

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.releases.CatalogPreset
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.UpdateCatalogDraft
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDataExample
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.contracts.commands.UpdateDataContract
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.BlockStylePreset
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The REST contract's request limits (2.0.0-beta.3), held by the commands too, so the UI and MCP
 * cannot write what the REST API refuses to take back in an update. Each case builds its command at
 * the limit, which must be accepted, and one past it, which must fail on the field named.
 *
 * The limits are written out here rather than read from [FieldLimits]: this test is the oracle for
 * the contract's numbers, so it catches a constant drifting from them as well as a command that
 * forgets to check.
 */
class RequestLimitValidationTest {
    private val tenantId = TenantId(TenantKey("testtenant"))
    private val catalogId = testCatalog(tenantId, CatalogKey.DEFAULT)
    private val templateId = TemplateId(TemplateKey("invoice"), catalogId)
    private val variantId = VariantId(VariantKey("english"), templateId)
    private val themeId = ThemeId(ThemeKey("mytheme"), catalogId)
    private val attributeId = AttributeId(AttributeKey.of("myattribute"), catalogId)

    private class Case(val label: String, val limit: Int, val field: String, val build: (Int) -> Any)

    private fun attributes(count: Int) = (1..count).associate { "attr-$it" to "v" }
    private fun examples(count: Int) = (1..count).map { DataExample(id = "example-$it", name = "Example $it", data = JsonNodeFactory.instance.objectNode()) }
    private fun presets(count: Int) = BlockStylePresets((1..count).associate { "preset-$it" to BlockStylePreset(label = "Preset $it", styles = emptyMap()) })
    private fun text(length: Int) = "x".repeat(length)

    private val cases = listOf(
        Case("CreateVariant.attributes", 50, "attributes") { n -> CreateVariant(variantId, "English", null, attributes(n), catalogVersion = null) },
        Case("UpdateVariant.attributes", 50, "attributes") { n -> UpdateVariant(variantId, "English", attributes(n), catalogVersion = null) },
        Case("CreateVariant attribute value", 255, "attributes.language") { n ->
            CreateVariant(variantId, "English", null, mapOf("language" to text(n)), catalogVersion = null)
        },
        Case("UpdateVariant attribute value", 255, "attributes.language") { n -> UpdateVariant(variantId, "English", mapOf("language" to text(n)), catalogVersion = null) },
        Case("CreateAttributeDefinition.allowedValues item", 255, "allowedValues") { n ->
            CreateAttributeDefinition(attributeId, "Language", allowedValues = listOf("nl", text(n)), catalogVersion = null)
        },
        Case("UpdateAttributeDefinition.allowedValues item", 255, "allowedValues") { n ->
            UpdateAttributeDefinition(attributeId, "Language", allowedValues = listOf("nl", text(n)), catalogVersion = null)
        },
        Case("CreateDocumentTemplate.dataExamples", 100, "dataExamples") { n -> CreateDocumentTemplate(templateId, "Invoice", catalogVersion = null, dataExamples = examples(n)) },
        Case("UpdateDataContract.dataExamples", 100, "dataExamples") { n -> UpdateDataContract(templateId, dataExamples = examples(n), catalogVersion = null) },
        Case("UpdateDataContract data example name", 255, "dataExamples[0].name") { n ->
            UpdateDataContract(templateId, dataExamples = listOf(examples(1).single().copy(name = text(n))), catalogVersion = null)
        },
        Case("UpdateDataExample.name", 255, "name") { n -> UpdateDataExample(templateId, "example-1", name = text(n), catalogVersion = null) },
        Case("CreateTheme.blockStylePresets", 100, "blockStylePresets") { n -> CreateTheme(themeId, "Corporate", blockStylePresets = presets(n), catalogVersion = null) },
        Case("UpdateTheme.blockStylePresets", 100, "blockStylePresets") { n -> UpdateTheme(themeId, blockStylePresets = presets(n), catalogVersion = null) },
        Case("UpdateCatalogDependencies.dependencies", 50, "dependencies") { n ->
            UpdateCatalogDependencies(
                catalogId,
                (1..n).map { DeclaredCatalogDependency("epistola.app", "acme", "catalog-$it", releaseVersion = "1.0.0") },
                catalogVersion = null,
            )
        },
        Case("UpdateCatalogDraft.presets", 100, "presets") { n ->
            UpdateCatalogDraft(catalogId, DraftRef.Id(UUID.randomUUID().toString()), presets = (1..n).map { CatalogPreset("preset-$it", "Preset $it") })
        },
        Case("ReleaseCatalogDraft.acknowledgedBehind", 1000, "acknowledgedBehind") { n ->
            ReleaseCatalogDraft(catalogId, acknowledgedBehind = (1..n).map { "1.0.$it" }.toSet(), catalogVersion = null)
        },
    )

    @Test
    fun `each limit accepts its maximum`() {
        val refused = cases.mapNotNull { case -> runCatching { case.build(case.limit) }.exceptionOrNull()?.let { "${case.label} at ${case.limit}: $it" } }
        assertEquals(emptyList(), refused)
    }

    @Test
    fun `each limit refuses one more, on the field it names`() {
        val wrong = cases.mapNotNull { case ->
            when (val thrown = runCatching { case.build(case.limit + 1) }.exceptionOrNull()) {
                is ValidationException -> if (thrown.field == case.field) null else "${case.label}: failed on '${thrown.field}', not '${case.field}'"
                null -> "${case.label}: accepted ${case.limit + 1}"
                else -> "${case.label}: threw ${thrown::class.simpleName} instead of ValidationException"
            }
        }
        assertEquals(emptyList(), wrong)
    }
}
