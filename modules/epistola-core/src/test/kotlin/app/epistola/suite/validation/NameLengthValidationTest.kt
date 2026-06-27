package app.epistola.suite.validation

import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.UpdateEnvironment
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Cross-layer oracle for #633 (server side): every user-editable NAME/TITLE
 * command must reject input longer than 100 characters at construction time. The
 * length check lives in each command's `init {}` block, so this is a pure unit
 * test — no Spring, no DB.
 *
 * The canonical limit (100) is duplicated here on purpose: this test is the
 * independent oracle, so it catches both a command drifting from the limit AND an
 * accidental change to [FieldLimits.MAX_NAME_LENGTH].
 */
class NameLengthValidationTest {

    private val tenantKey = TenantKey("testtenant")
    private val tenantId = TenantId(tenantKey)
    private val catalogId = CatalogId(CatalogKey.DEFAULT, tenantId)
    private val templateId = TemplateId(TemplateKey("invoice"), catalogId)
    private val variantId = VariantId(VariantKey("english"), templateId)
    private val stencilId = StencilId(StencilKey("mystencil"), catalogId)
    private val themeId = ThemeId(ThemeKey("mytheme"), catalogId)
    private val codeListId = CodeListId(CodeListKey.of("mycodelist"), catalogId)
    private val attributeId = AttributeId(AttributeKey.of("myattribute"), catalogId)
    private val environmentId = EnvironmentId(EnvironmentKey.of("myenvironment"), tenantId)

    /** label -> builder that puts the given string in the command's name/title field. */
    private val cases: List<Pair<String, (String) -> Any>> = listOf(
        "CreateTenant.name" to { s -> CreateTenant(tenantKey, s) },
        "CreateTheme.name" to { s -> CreateTheme(themeId, s) },
        "UpdateTheme.name" to { s -> UpdateTheme(themeId, name = s) },
        "CreateDocumentTemplate.name" to { s -> CreateDocumentTemplate(templateId, s) },
        "UpdateDocumentTemplate.name" to { s -> UpdateDocumentTemplate(templateId, name = s) },
        "CreateStencil.name" to { s -> CreateStencil(stencilId, s) },
        "UpdateStencil.name" to { s -> UpdateStencil(stencilId, name = s) },
        "CreateCatalog.name" to { s -> CreateCatalog(tenantKey, CatalogKey("mycatalog"), s) },
        "CreateVariant.title" to { s -> CreateVariant(variantId, title = s, description = null) },
        "UpdateVariant.title" to { s -> UpdateVariant(variantId, title = s, attributes = emptyMap()) },
        "ImportFont.name" to { s -> ImportFont(tenantId, CatalogKey.DEFAULT, slug = "myfont", name = s, kind = "sans") },
        "CreateApiKey.name" to { s -> CreateApiKey(tenantKey, s) },
        // URL-sourced so only the displayName length can fail (INLINE would also require entries).
        "CreateCodeList.displayName" to { s ->
            CreateCodeList(codeListId, displayName = s, sourceType = CodeListSource.URL, sourceUrl = "https://example.com/codes.json")
        },
        "CreateAttributeDefinition.displayName" to { s -> CreateAttributeDefinition(attributeId, displayName = s) },
        "UpdateAttributeDefinition.displayName" to { s -> UpdateAttributeDefinition(attributeId, displayName = s) },
        "CreateEnvironment.name" to { s -> CreateEnvironment(environmentId, s) },
        "UpdateEnvironment.name" to { s -> UpdateEnvironment(environmentId, s) },
    )

    @Test
    fun `the canonical name length limit is 100`() {
        // Independent oracle: pins the policy value so a change to the constant
        // (and thus to every command referencing it) fails loudly here.
        assertEquals(100, FieldLimits.MAX_NAME_LENGTH)
    }

    @Test
    fun `name and title fields reject input longer than 100 characters`() {
        val tooLong = "x".repeat(101)
        val violations = cases.mapNotNull { (label, build) ->
            when (val thrown = runCatching { build(tooLong) }.exceptionOrNull()) {
                is ValidationException -> null
                null -> "$label: accepted a 101-char value (no length validation)"
                else -> "$label: threw ${thrown::class.simpleName} instead of ValidationException"
            }
        }
        if (violations.isNotEmpty()) {
            fail("Name/title length validation missing or wrong (issue #633):\n" + violations.joinToString("\n"))
        }
    }

    @Test
    fun `name and title fields accept input of exactly 100 characters`() {
        val atLimit = "x".repeat(100)
        for ((label, build) in cases) {
            val thrown = runCatching { build(atLimit) }.exceptionOrNull()
            if (thrown is ValidationException) {
                fail("$label: rejected a 100-char value: ${thrown.message}")
            }
        }
    }
}
