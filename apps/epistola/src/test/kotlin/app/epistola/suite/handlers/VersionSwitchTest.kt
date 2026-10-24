// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.htmx.CatalogSegment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/** Switching versions keeps the page someone was on, in the version they switch to. */
@Tag("unit")
class VersionSwitchTest {

    private val work = "k7m2x9qa"
    private val next = CatalogSegment(CatalogKey.of("letters"), namespace = "acme")
    private val redesign = CatalogSegment(CatalogKey.of("letters"), work, "acme")

    private fun switch(from: String, to: CatalogSegment) = VersionSwitch.samePage(from, "acme", to)

    @Test
    fun `a resource page stays the same page in the other version`() {
        assertThat(switch("/tenants/acme/catalogs/acme/letters/templates/invoice/variants/standard/editor", redesign))
            .isEqualTo("/tenants/acme/catalogs/acme/letters@$work/templates/invoice/variants/standard/editor")
        assertThat(switch("/tenants/acme/catalogs/acme/letters@$work/stencils/header?tab=usage", next)).isEqualTo("/tenants/acme/catalogs/acme/letters/stencils/header?tab=usage")
    }

    @Test
    fun `a list stays the same list`() {
        assertThat(switch("/tenants/acme/catalogs/acme/letters/templates", redesign)).isEqualTo("/tenants/acme/catalogs/acme/letters@$work/templates")
        assertThat(switch("/tenants/acme/catalogs/acme/letters@$work/themes/new", next)).`as`("a create form too").isEqualTo("/tenants/acme/catalogs/acme/letters/themes/new")
        assertThat(switch("/tenants/acme/themes/search?catalog=letters%40$work", next)).`as`("a tenant-level partial is no page of the catalog").isNull()
    }

    @Test
    fun `the overview goes to the other version's overview, and pages shared by every version stay`() {
        assertThat(switch("/tenants/acme/catalogs/acme/letters/browse", redesign)).isEqualTo("/tenants/acme/catalogs/acme/letters@$work/draft")
        assertThat(switch("/tenants/acme/catalogs/acme/letters@$work/draft", next)).isEqualTo("/tenants/acme/catalogs/acme/letters/browse")
        assertThat(switch("/tenants/acme/catalogs/acme/letters@$work/settings", next)).isEqualTo("/tenants/acme/catalogs/acme/letters/settings")
        assertThat(switch("/tenants/acme/catalogs/acme/letters/deployments", redesign)).isEqualTo("/tenants/acme/catalogs/acme/letters/deployments")
    }

    @Test
    fun `anything else goes to the overview`() {
        assertThat(switch("/tenants/acme/catalogs/acme/invoices/templates/invoice", redesign)).`as`("another catalog").isNull()
        assertThat(switch("/tenants/other/catalogs/acme/letters/templates/invoice", redesign)).`as`("another tenant").isNull()
        assertThat(switch("https://evil.example/tenants/acme/templates/letters", redesign)).isNull()
        assertThat(switch("/tenants/acme//evil.example", redesign)).isNull()
        assertThat(switch("/tenants/acme/environments/test", redesign)).`as`("not a catalog page").isNull()
    }
}
