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

    private val work = UUID.fromString("0193a6b2-7f4e-7c1d-9a2b-3c4d5e6f7a8b")
    private val next = CatalogSegment(CatalogKey.of("letters"))
    private val redesign = CatalogSegment(CatalogKey.of("letters"), work)

    private fun switch(from: String, to: CatalogSegment) = VersionSwitch.samePage(from, "acme", to)

    @Test
    fun `a resource page stays the same page in the other version`() {
        assertThat(switch("/tenants/acme/catalogs/letters/templates/invoice/variants/standard/editor", redesign))
            .isEqualTo("/tenants/acme/catalogs/letters@$work/templates/invoice/variants/standard/editor")
        assertThat(switch("/tenants/acme/catalogs/letters@$work/stencils/header?tab=usage", next)).isEqualTo("/tenants/acme/catalogs/letters/stencils/header?tab=usage")
    }

    @Test
    fun `a list stays the same list`() {
        assertThat(switch("/tenants/acme/catalogs/letters/templates", redesign)).isEqualTo("/tenants/acme/catalogs/letters@$work/templates")
        assertThat(switch("/tenants/acme/themes?q=brand&catalog=letters%40$work", next)).isEqualTo("/tenants/acme/themes?q=brand&catalog=letters")
    }

    @Test
    fun `the overview goes to the other version's overview, and pages shared by every version stay`() {
        assertThat(switch("/tenants/acme/catalogs/letters/browse", redesign)).isEqualTo("/tenants/acme/catalogs/letters@$work/draft")
        assertThat(switch("/tenants/acme/catalogs/letters@$work/draft", next)).isEqualTo("/tenants/acme/catalogs/letters/browse")
        assertThat(switch("/tenants/acme/catalogs/letters@$work/settings", next)).isEqualTo("/tenants/acme/catalogs/letters/settings")
        assertThat(switch("/tenants/acme/catalogs/letters/deployments", redesign)).isEqualTo("/tenants/acme/catalogs/letters/deployments")
    }

    @Test
    fun `anything else goes to the overview`() {
        assertThat(switch("/tenants/acme/catalogs/invoices/templates/invoice", redesign)).`as`("another catalog").isNull()
        assertThat(switch("/tenants/other/catalogs/letters/templates/invoice", redesign)).`as`("another tenant").isNull()
        assertThat(switch("https://evil.example/tenants/acme/templates/letters", redesign)).isNull()
        assertThat(switch("/tenants/acme//evil.example", redesign)).isNull()
        assertThat(switch("/tenants/acme/environments/test", redesign)).`as`("not a catalog page").isNull()
    }
}
