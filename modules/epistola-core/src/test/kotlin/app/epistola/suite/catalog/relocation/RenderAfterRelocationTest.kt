// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.fonts.model.FontIntegrityException
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.templates.model.Node
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * What a crude move does to a published document that depends on the moved resource. Each test
 * publishes a template, relocates what it uses, and renders the published version for real. The
 * resolution queries alone (covered in [RuntimeResolutionAfterRelocationTest]) do not exercise what
 * the renderer runs first, notably the font integrity check against the fingerprints pinned at
 * publish -- which is what makes a moved font fail loudly rather than render in the wrong face.
 *
 * Content embedded or snapshotted at publish -- a stencil's insertion, the frozen theme -- keeps
 * rendering; content resolved by address at render time does not.
 */
class RenderAfterRelocationTest : RelocationTestSupport() {

    /**
     * The theme and template live in `archive`, so moving the font from `letters` creates no catalog
     * cycle and the only question is whether the published document still renders.
     */
    @Test
    fun `a published document fails to render after its theme's font family moves`() {
        val tenant = tenantWith("Render after font move", listOf(letters, shared, archive))
        importFont(tenant, letters, "acme")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, archive)), "Brand", documentStyles = fontStyle("acme", letters.value)).execute() }
        val version = publishTemplate(tenant, archive, textModel(usesTheme("brand", archive.value).themeRef))
        assertPreviewRenders(tenant, archive, version)

        move(tenant, address(CatalogResourceType.FONT, letters, "acme").movedTo(shared))

        assertThatThrownBy { assertPreviewRenders(tenant, archive, version) }.isInstanceOf(FontIntegrityException::class.java)
    }

    @Test
    fun `a published document fails to render after its theme's font family is renamed`() {
        val tenant = tenantWith("Render after font rename")
        importFont(tenant, letters, "acme")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand", documentStyles = fontStyle("acme", letters.value)).execute() }
        val version = publishTemplate(tenant, letters, textModel(usesTheme("brand", letters.value).themeRef))
        assertPreviewRenders(tenant, letters, version)

        move(tenant, address(CatalogResourceType.FONT, letters, "acme").renamedTo("acme-grotesk"))

        assertThatThrownBy { assertPreviewRenders(tenant, letters, version) }.isInstanceOf(FontIntegrityException::class.java)
    }

    @Test
    fun `a published document still renders after its theme moves`() {
        val tenant = tenantWith("Render after theme move", listOf(letters, shared, archive))
        importFont(tenant, letters, "acme")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand", documentStyles = fontStyle("acme", letters.value)).execute() }
        val version = publishTemplate(tenant, archive, textModel(usesTheme("brand", letters.value).themeRef))

        move(tenant, address(CatalogResourceType.THEME, letters, "brand").movedTo(shared))

        assertPreviewRenders(tenant, archive, version)
    }

    @Test
    fun `a published document still renders after the image it shows moves, without the image`() {
        val tenant = tenantWith("Render after image move")
        uploadPng(tenant, letters, "logo", renderablePng())
        val version = publishTemplate(tenant, letters, singleNodeModel(Node(id = "logo", type = "image", props = mapOf("assetId" to "logo", "catalogKey" to letters.value))))

        move(tenant, address(CatalogResourceType.IMAGE, letters, "logo").movedTo(shared))

        assertPreviewRenders(tenant, letters, version)
    }

    @Test
    fun `a published document still renders after the stencil it inserts moves and is renamed`() {
        val tenant = tenantWith("Render after stencil move")
        val header = StencilId(StencilKey.of("header"), catalogId(tenant, letters))
        val version = publishTemplate(tenant, letters, templateEmbedding("header", letters.value)) {
            CreateStencil(header, "Header").execute()
            PublishStencilVersion(StencilVersionId(VersionKey.of(1), header)).execute()
        }

        move(tenant, address(CatalogResourceType.STENCIL, letters, "header").movedTo(shared, "masthead"))

        assertPreviewRenders(tenant, letters, version)
    }
}
