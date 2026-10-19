// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.BrowseResource
import app.epistola.suite.catalog.queries.ResourceStatus
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.security.Permission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A catalog version's contents, grouped by kind, with a way to add each kind there. */
class CatalogContentsTest {

    private val acme = TenantKey.of("acme")
    private val work = java.util.UUID.fromString("0193a6b2-7f4e-7c1d-9a2b-3c4d5e6f7a8b")

    private fun resource(type: String, slug: String, name: String) = BrowseResource(type, slug, name, null, ResourceStatus.INSTALLED)

    @Test
    fun `every kind is listed, templates first, each resource linked into the version and sorted by name`() {
        val contents = CatalogContents.of(
            acme,
            CatalogSegment(CatalogKey.of("letters"), work),
            listOf(resource("theme", "brand", "Brand"), resource("template", "reminder", "Reminder"), resource("template", "invoice", "Invoice")),
        ) { true }

        assertThat(contents.groups.map { it.type }).containsExactly("template", "stencil", "theme", "image", "font", "attribute", "codeList")
        val templates = contents.groups.first()
        assertThat(templates.items.map { it.name }).containsExactly("Invoice", "Reminder")
        assertThat(templates.items.first().href).isEqualTo("/tenants/acme/templates/letters@$work/invoice")
        assertThat(templates.newHref).isEqualTo("/tenants/acme/templates/new?catalog=letters@$work")
        assertThat(contents.groups.first { it.type == "stencil" }.items).isEmpty()
        assertThat(contents.filled.map { it.type }).containsExactly("template", "theme")
        assertThat(contents.addable.map { it.type }).containsExactly("stencil", "image", "font", "attribute", "codeList")
    }

    @Test
    fun `a kind without a page of its own links to its list on the catalog, and New is withheld without the permission`() {
        val contents = CatalogContents.of(acme, CatalogSegment(CatalogKey.of("letters")), listOf(resource("image", "logo", "Logo"))) {
            it != Permission.TENANT_SETTINGS
        }

        assertThat(contents.groups.first { it.type == "image" }.items.single().href).isEqualTo("/tenants/acme/catalogs/letters/images")
        assertThat(contents.groups.first { it.type == "codeList" }.createWithheld).isTrue()
        assertThat(contents.withheldLabels).containsExactly("attributes", "code lists")
        assertThat(contents.addable.map { it.type }).doesNotContain("attribute", "codeList")
        assertThat(contents.groups.first { it.type == "template" }.mayCreate).isTrue()
    }

    @Test
    fun `each kind counts what there is and what is not published yet, and links to its list in the version`() {
        val contents = CatalogContents.of(
            acme,
            CatalogSegment(CatalogKey.of("letters"), work),
            listOf(resource("template", "invoice", "Invoice"), resource("template", "reminder", "Reminder")),
            mapOf("template/reminder" to app.epistola.suite.catalog.queries.CatalogResourceState.NEW),
        ) { true }

        val templates = contents.templates
        assertThat(templates.count to templates.changedCount).isEqualTo(2 to 1)
        assertThat(templates.listHref).isEqualTo("/tenants/acme/catalogs/letters@$work/templates")
        assertThat(contents.groups.first { it.type == "codeList" }.listHref).isEqualTo("/tenants/acme/catalogs/letters@$work/code-lists")
    }
}
