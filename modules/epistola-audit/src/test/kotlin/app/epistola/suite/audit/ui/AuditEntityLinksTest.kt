// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit.ui

import app.epistola.suite.audit.AuditEntry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Fast unit cover for the entity label/link mapping — readable labels and resource links derived from
 * an audited entity's type + id path, with sensible fallbacks for unmapped/typeless entries.
 */
class AuditEntityLinksTest {
    private val links = AuditEntityLinks()

    private fun entry(type: String?, id: String?) = AuditEntry(
        id = UUID.randomUUID(),
        occurredAt = OffsetDateTime.parse("2026-06-10T08:00:00Z"),
        tenantKey = null,
        actorUserId = null,
        actorDisplayName = null,
        action = "X",
        operation = "WRITE",
        entityType = type,
        entityId = id,
        outcome = "SUCCESS",
        errorCode = null,
        details = emptyMap(),
        instanceId = null,
    )

    @Test
    fun `a variant labels template plus variant and links to the template editor`() {
        val link = links.resolve(entry("variant", "demo/default/invoice/nl"))
        assertThat(link.label).isEqualTo("template invoice › variant nl")
        assertThat(link.href).isEqualTo("/tenants/demo/catalogs/default/templates/invoice")
    }

    @Test
    fun `a template links to its editor`() {
        val link = links.resolve(entry("template", "demo/default/invoice"))
        assertThat(link.label).isEqualTo("template invoice")
        assertThat(link.href).isEqualTo("/tenants/demo/catalogs/default/templates/invoice")
    }

    @Test
    fun `a theme links to the catalog-scoped theme page`() {
        val link = links.resolve(entry("theme", "demo/default/corporate"))
        assertThat(link.label).isEqualTo("theme corporate")
        assertThat(link.href).isEqualTo("/tenants/demo/catalogs/default/themes/corporate")
    }

    @Test
    fun `an environment links to the tenant-scoped environment page`() {
        val link = links.resolve(entry("environment", "demo/production"))
        assertThat(link.label).isEqualTo("environment production")
        assertThat(link.href).isEqualTo("/tenants/demo/environments/production")
    }

    @Test
    fun `an unmapped type keeps the full path and has no link`() {
        val link = links.resolve(entry("asset", "demo/default/abc"))
        assertThat(link.label).isEqualTo("asset demo/default/abc")
        assertThat(link.href).isNull()
    }

    @Test
    fun `a typeless entry falls back to the raw id with no link`() {
        val link = links.resolve(entry(null, "demo"))
        assertThat(link.label).isEqualTo("demo")
        assertThat(link.href).isNull()
    }

    @Test
    fun `an attribute or a font, without a page of its own, links to its catalog's list`() {
        assertThat(links.resolve(entry("attribute", "demo/default/brand")).href).isEqualTo("/tenants/demo/catalogs/default/attributes")
        assertThat(links.resolve(entry("font", "demo/default/inter")).href).isEqualTo("/tenants/demo/catalogs/default/fonts")
    }

    @Test
    fun `an id naming its catalog's namespace links to the catalog by namespace and key`() {
        // Written since catalogs are addressed by namespace and key: `tenant/namespace/catalog/…`.
        val variant = links.resolve(entry("variant", "upgrade/upgrade/weni-werk-brieven/ad-hoc-brief/default"))
        assertThat(variant.label).isEqualTo("template ad-hoc-brief › variant default")
        assertThat(variant.href).isEqualTo("/tenants/upgrade/catalogs/upgrade/weni-werk-brieven/templates/ad-hoc-brief")

        val stencil = links.resolve(entry("stencil", "upgrade/upgrade/weni-huisstijl/weni-adres"))
        assertThat(stencil.label).isEqualTo("stencil weni-adres")
        assertThat(stencil.href).isEqualTo("/tenants/upgrade/catalogs/upgrade/weni-huisstijl/stencils/weni-adres")

        assertThat(links.resolve(entry("font", "acme/acme/brand/inter")).href).isEqualTo("/tenants/acme/catalogs/acme/brand/fonts")
    }
}
