// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit.ui

import app.epistola.suite.audit.AuditEntry
import org.springframework.stereotype.Component

/** A readable label for an audited entity, plus an optional link to its resource page. */
data class AuditEntityLink(val label: String, val href: String?)

/**
 * Turns an audited entity (its `entity_type` + slash-separated id path, e.g. `variant` +
 * `demo/default/invoice/nl`) into a human label and, where one exists, a link to that resource's UI
 * page — so the Audit viewer reads "template invoice › variant nl" linking to the template editor,
 * not "variant demo/default/invoice/nl".
 *
 * The id path mirrors the [app.epistola.suite.common.ids.EntityId] hierarchy: catalog-scoped
 * resources are `tenant/catalog/key`, things under a template (variant/version/contract) carry the
 * template in the path and link to the template editor, etc.
 *
 * TODO: the resource URL patterns are hard-coded here, which couples this audit module to the host
 * app's routes. When a second surface needs entity links (or feature modules want to link their own
 * entities), replace this with a generic `AuditEntityLinkResolver` SPI that the host app and feature
 * modules implement for their own entity types — mirroring the other contributor SPIs.
 */
@Component("auditEntityLinks")
class AuditEntityLinks {

    fun resolve(entry: AuditEntry): AuditEntityLink {
        val type = entry.entityType
        val id = entry.entityId
        // EntityIdentifiable-only entries carry no typed path (e.g. a tenant slug, a document id) —
        // show what we have, no link.
        if (type == null || id.isNullOrBlank()) return AuditEntityLink(label = id ?: "—", href = null)

        val seg = id.split("/")
        fun s(i: Int) = seg.getOrNull(i)

        return when (type) {
            "template" -> AuditEntityLink("template ${s(2)}", templateHref(seg))
            "variant" -> AuditEntityLink("template ${s(2)} › variant ${s(3)}", templateHref(seg))
            "version" -> AuditEntityLink("template ${s(2)} › variant ${s(3)} › v${s(4)}", templateHref(seg))
            "contract-version" -> AuditEntityLink("template ${s(2)} › contract v${s(3)}", templateHref(seg))
            "theme" -> AuditEntityLink("theme ${s(2)}", catalogScoped("themes", seg))
            "stencil" -> AuditEntityLink("stencil ${s(2)}", catalogScoped("stencils", seg))
            "stencil-version" -> AuditEntityLink("stencil ${s(2)} › v${s(3)}", catalogScoped("stencils", seg))
            "attribute" -> AuditEntityLink("attribute ${s(2)}", catalogScoped("attributes", seg))
            "code-list" -> AuditEntityLink("code list ${s(2)}", catalogScoped("code-lists", seg))
            "font" -> AuditEntityLink("font ${s(2)}", catalogScoped("fonts", seg))
            "environment" -> AuditEntityLink("environment ${s(1)}", s(0)?.let { "/tenants/$it/environments/${s(1)}" })
            // Unmapped but typed: keep the full path for context, no link.
            else -> AuditEntityLink("$type $id", href = null)
        }
    }

    /** `/tenants/{tenant}/{collection}/{catalog}/{key}` for a catalog-scoped resource `tenant/catalog/key/…`. */
    private fun catalogScoped(collection: String, seg: List<String>): String? {
        val (tenant, catalog, key) = Triple(seg.getOrNull(0), seg.getOrNull(1), seg.getOrNull(2))
        return if (tenant != null && catalog != null && key != null) "/tenants/$tenant/$collection/$catalog/$key" else null
    }

    /** Variants/versions/contract-versions live under the template editor, identified by `tenant/catalog/template`. */
    private fun templateHref(seg: List<String>): String? = catalogScoped("templates", seg)
}
