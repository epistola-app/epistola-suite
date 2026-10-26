// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.catalog.protocol.ThemeResource
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A theme's baseline block margins (`defaultBlockStyles`, contract `2.0.0-alpha.3`) are stored,
 * edited, exported and resolved for rendering, and hold margins only.
 */
class ThemeBlockSpacingTest : IntegrationTestBase() {

    @Autowired private lateinit var contentBuilder: CatalogContentBuilder

    @Autowired private lateinit var resolver: ThemeStyleResolver

    private val catalog = CatalogKey.of("spaced")

    @Test
    fun `a theme keeps its baseline margins through save, edit, export and style resolution`() {
        val tenant = createTenant("Block spacing").id
        val id = ThemeId(ThemeKey.of("airy"), testCatalog(tenant, catalog))
        withMediator {
            CreateCatalog(tenantKey = tenant, id = catalog, name = "Spaced").execute()
            CreateTheme(id = id, name = "Airy", defaultBlockStyles = mapOf("marginBottom" to "10pt")).execute()
            assertThat(GetTheme(id).query()!!.defaultBlockStyles).isEqualTo(mapOf("marginBottom" to "10pt"))

            UpdateTheme(id, defaultBlockStyles = mapOf("marginTop" to "2sp", "marginBottom" to "8pt")).execute()
            assertThat(GetTheme(id).query()!!.defaultBlockStyles).isEqualTo(mapOf("marginTop" to "2sp", "marginBottom" to "8pt"))

            val exported = contentBuilder.build(testCatalog(tenant, catalog)).resourceDetails.getValue("theme/airy").resource as ThemeResource
            assertThat(exported.defaultBlockStyles).isEqualTo(mapOf("marginTop" to "2sp", "marginBottom" to "8pt"))

            val resolved = resolver.resolveStyles(testCatalog(tenant, catalog), ThemeKey.of("airy"), null, TemplateDocument(root = "root", nodes = emptyMap(), slots = emptyMap()))
            assertThat(resolved.defaultBlockStyles).isEqualTo(mapOf("marginTop" to "2sp", "marginBottom" to "8pt"))

            UpdateTheme(id, clearDefaultBlockStyles = true).execute()
            assertThat(GetTheme(id).query()!!.defaultBlockStyles).isNull()
        }
    }

    @Test
    fun `a baseline holds margins only`() {
        val tenant = createTenant("Block spacing keys").id
        assertThatThrownBy {
            withMediator {
                CreateCatalog(tenantKey = tenant, id = catalog, name = "Spaced").execute()
                CreateTheme(id = ThemeId(ThemeKey.of("loud"), testCatalog(tenant, catalog)), name = "Loud", defaultBlockStyles = mapOf("color" to "red")).execute()
            }
        }.isInstanceOf(ValidationException::class.java)
    }
}
