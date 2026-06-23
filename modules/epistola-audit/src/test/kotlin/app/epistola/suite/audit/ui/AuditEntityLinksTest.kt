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
        assertThat(link.href).isEqualTo("/tenants/demo/templates/default/invoice")
    }

    @Test
    fun `a template links to its editor`() {
        val link = links.resolve(entry("template", "demo/default/invoice"))
        assertThat(link.label).isEqualTo("template invoice")
        assertThat(link.href).isEqualTo("/tenants/demo/templates/default/invoice")
    }

    @Test
    fun `a theme links to the catalog-scoped theme page`() {
        val link = links.resolve(entry("theme", "demo/default/corporate"))
        assertThat(link.label).isEqualTo("theme corporate")
        assertThat(link.href).isEqualTo("/tenants/demo/themes/default/corporate")
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
}
