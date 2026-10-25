// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.codelists

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.commands.UpdateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.default
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * A release's code list opens read-only at its address, `…/catalogs/{namespace}/{key}@{version}/code-lists/{key}`
 * (ADR 0033): the release's content, not the draft's, and nothing on the page changes it.
 */
class ReleasedCodeListPageHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a released code list opens read-only, as the release holds it`() {
        val tenant = createTenant("Released code list page")
        val catalog = CatalogId.default(TenantId(tenant.id))
        val id = CodeListId(CodeListKey.of("regions"), catalog)
        withMediator {
            CreateCodeList(id, "Regions as released", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("eu", "Europe"))).execute()
            MarkCatalogReady(catalog).execute()
            ReleaseCatalogDraft(catalog, "1.0.0").execute()
            UpdateCodeList(id, "Regions as drafted", entries = listOf(CodeListEntry("us", "United States"))).execute()
        }

        val page = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${catalog.address}@1.0.0/code-lists/regions", String::class.java)

        assertThat(page.statusCode.value()).isEqualTo(200)
        assertThat(page.body.orEmpty()).contains("Regions as released", "Europe").doesNotContain("Regions as drafted", "United States")
        assertThat(page.body.orEmpty()).doesNotContain("/hidden").doesNotContain("Delete code list")
    }
}
