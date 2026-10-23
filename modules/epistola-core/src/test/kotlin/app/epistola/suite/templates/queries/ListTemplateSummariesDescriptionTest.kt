// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A template summary carries the template's description, for the lists that show one. */
class ListTemplateSummariesDescriptionTest : IntegrationTestBase() {

    @Test
    fun `a summary carries the template's description, and none for a template without one`() {
        val tenant = TenantId(createTenant("Summary Descriptions").id)
        val catalog = CatalogId.default(tenant)

        val summaries = withMediator {
            CreateDocumentTemplate(TemplateId(TemplateKey.of("described"), catalog), "Described", description = "For residents").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("plain"), catalog), "Plain").execute()
            ListTemplateSummaries(tenant, catalogKey = catalog.key).query().items
        }

        assertThat(summaries.associate { it.id.value to it.description })
            .containsEntry("described", "For residents")
            .containsEntry("plain", null)
    }
}
