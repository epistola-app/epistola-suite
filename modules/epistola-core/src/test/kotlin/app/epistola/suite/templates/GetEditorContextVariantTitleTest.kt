// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetEditorContext
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.default
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The editor names the variant it opens by its title, as the template page does, not by its key. */
class GetEditorContextVariantTitleTest : IntegrationTestBase() {

    @Test
    fun `the editor context carries the variant's title`() = fixture {
        lateinit var variantId: VariantId
        given {
            val tenant = tenant("Editor Variant Title")
            val template = template(tenant, "Letter")
            val variant = variant(tenant, template, title = "Dutch letter")
            variantId = VariantId(variant.id, TemplateId(template.id, CatalogId.default(TenantId(tenant.id))))
        }
        whenever { GetEditorContext(variantId, catalogVersion = null).query() }
        then {
            assertThat(result<app.epistola.suite.templates.queries.EditorContext?>()!!.variantTitle).isEqualTo("Dutch letter")
        }
    }
}
