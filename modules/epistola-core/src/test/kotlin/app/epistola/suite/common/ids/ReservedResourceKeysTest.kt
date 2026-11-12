// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.common.ids

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

/**
 * One list of reserved words for every resource key, refused where an author chooses a key and
 * never where one is read ([ReservedResourceKeys]).
 */
class ReservedResourceKeysTest {

    private val tenant = TenantId(TenantKey.of("acme"))
    private val catalog = CatalogId("acme", CatalogKey.of("letters"), tenant)
    private val template = TemplateId(TemplateKey.of("letter"), catalog)

    /** Every command that lets an author choose a resource key, by the kind its refusal names. */
    private val creates: Map<String, (String) -> Any> = mapOf(
        "Template" to { key -> CreateDocumentTemplate(TemplateId(TemplateKey.of(key), catalog), "Letter", catalogVersion = null) },
        "Variant" to { key -> CreateVariant(VariantId(VariantKey.of(key), template), "Dutch", description = null, catalogVersion = null) },
        "Stencil" to { key -> CreateStencil(StencilId(StencilKey.of(key), catalog), "Header", catalogVersion = null) },
        "Theme" to { key -> CreateTheme(ThemeId(ThemeKey.of(key), catalog), "Corporate", catalogVersion = null) },
        "Attribute" to { key -> CreateAttributeDefinition(AttributeId(AttributeKey.of(key), catalog), "Language", catalogVersion = null) },
        "Code list" to { key ->
            CreateCodeList(
                CodeListId(CodeListKey.of(key), catalog),
                displayName = "Countries",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry(code = "nl", label = "Netherlands")),
                catalogVersion = null,
            )
        },
        "Image" to { key -> UploadAsset(catalog, "Logo", AssetMediaType("image/png"), ByteArray(0), null, null, AssetKey.of(key), catalogVersion = null) },
        "Environment" to { key -> CreateEnvironment(EnvironmentId(EnvironmentKey.of(key), tenant), "Test") },
    )

    @Test
    fun `no resource can be created with a reserved word as its key`() {
        assertSoftly { softly ->
            for ((kind, create) in creates) {
                for (word in ReservedResourceKeys.WORDS) {
                    softly.assertThatThrownBy { create(word) }
                        .`as`("$kind keyed $word")
                        .isInstanceOfSatisfying(ValidationException::class.java) {
                            assertThat(it.field).isEqualTo("key")
                            assertThat(it.code).isEqualTo(ValidationCode.KEY_RESERVED)
                        }
                        .hasMessage("$kind ID '$word' is reserved and cannot be used")
                }
            }
        }
    }

    @Test
    fun `a key that only contains a reserved word is fine`() {
        for ((kind, create) in creates - "Image") {
            assertThat(create("newsletter")).`as`(kind).isNotNull()
        }
    }

    @Test
    fun `a key stored as a reserved word still reads`() {
        // Themes, code lists, attributes and images accepted these words before; a catalog import may bring any.
        for (word in ReservedResourceKeys.WORDS) {
            listOf(TemplateKey.of(word), VariantKey.of(word), StencilKey.of(word), ThemeKey.of(word), AttributeKey.of(word), CodeListKey.of(word), AssetKey.of(word), FontKey.of(word), EnvironmentKey.of(word))
                .forEach { assertThat(it.value).isEqualTo(word) }
        }
    }
}
