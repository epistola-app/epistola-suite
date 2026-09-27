// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Every movable type through every shape of relocation: a move, a rename, and both at once.
 *
 * The earlier tests exercised whichever combination a scenario needed, which left most of this
 * grid untried -- and a rename bug once hid in exactly such a gap (see
 * [RuntimeResolutionAfterRelocationTest]). For each cell: the same identity lives at the new
 * address, the old one is left empty, and the rows the resource owns came along.
 */
class RelocationTypeMatrixTest : RelocationTestSupport() {

    enum class Shape { MOVE, RENAME, MOVE_AND_RENAME }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cells")
    fun `a relocation keeps identity, vacates the old address, and carries what the resource owns`(movable: MovableResource, shape: Shape) {
        val tenant = tenantWith("Matrix ${movable.name.lowercase()} ${shape.name.lowercase()}")
        val source = create(tenant, movable, letters, "subject")
        val relocation = when (shape) {
            Shape.MOVE -> source.movedTo(shared)
            Shape.RENAME -> source.renamedTo("renamed")
            Shape.MOVE_AND_RENAME -> source.movedTo(shared, "renamed")
        }
        val identity = identityAt(tenant, source)

        if (!movable.renameable && shape != Shape.MOVE) {
            assertThat(preview(tenant, relocation).blockers.map { it.code }).containsExactly("rename-unsupported")
            return
        }

        move(tenant, relocation)

        assertThat(identityAt(tenant, relocation.target)).isEqualTo(identity)
        assertThat(identityAt(tenant, source)).isNull()

        assertOwnedRowsFollowed(tenant, movable, relocation.target)
    }

    /** Reads what [movable] owns through its public query at [target]: the proof that it moved as a whole. */
    private fun assertOwnedRowsFollowed(tenant: TenantKey, movable: MovableResource, target: ResourceAddress) {
        val catalog = catalogId(tenant, CatalogKey.of(target.catalogKey))
        withMediator {
            when (movable) {
                MovableResource.STENCIL ->
                    assertThat(ListStencilVersions(StencilId(StencilKey.of(target.key), catalog)).query()).isNotEmpty()

                MovableResource.ATTRIBUTE ->
                    assertThat(GetAttributeDefinition(AttributeId(AttributeKey.of(target.key), catalog)).query()).isNotNull()

                MovableResource.TEMPLATE -> {
                    val templateId = TemplateId(TemplateKey.of(target.key), catalog)
                    assertThat(GetDocumentTemplate(templateId).query()).isNotNull()
                    assertThat(GetDraft(VariantId(VariantKey.INITIAL, templateId)).query()).isNotNull()
                }

                MovableResource.CODE_LIST ->
                    assertThat(ListCodeListEntries(CodeListId(CodeListKey.of(target.key), catalog)).query().map { it.code })
                        .containsExactlyInAnyOrder("nl", "en")

                MovableResource.ASSET ->
                    assertThat(GetAssetContent(tenant, AssetKey.of(target.key), CatalogKey.of(target.catalogKey)).query()).isNotNull()

                MovableResource.FONT ->
                    assertThat(ResolveFontFace(tenant, CatalogKey.of(target.catalogKey), FontKey.of(target.key), 400, italic = false).query())
                        .isEqualTo(ttfBytes())

                MovableResource.THEME ->
                    assertThat(GetTheme(ThemeId(ThemeKey.of(target.key), catalog)).query()).isNotNull()
            }
        }
    }

    companion object {
        @JvmStatic
        fun cells(): List<Arguments> = MovableResource.entries.flatMap { movable ->
            Shape.entries.map { shape -> Arguments.of(movable, shape) }
        }
    }
}
