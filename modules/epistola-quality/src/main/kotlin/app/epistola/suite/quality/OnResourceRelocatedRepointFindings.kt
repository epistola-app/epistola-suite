// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Repoints a relocated template's findings and ignores at its new address.
 *
 * Findings carry `subject_urn` and ignores carry `ignore_scope_urn`, both built from
 * `EntityId.toUrn()` — which composes the resource's *address*
 * (`urn:epistola:template:tenantA/letters/invoice`). `ignore_scope_urn` is also the join condition
 * between a finding and its ignore, and part of the ignore's primary key.
 *
 * Without this, a relocation would leave ignores holding the old address while the next submission
 * carries the new one. The join would stop matching and **every ignored finding would silently
 * reappear as open**, losing an author's triage — the "IGNORED is derived from a live ignore row"
 * invariant that `docs/quality.md` calls out as easy to break.
 *
 * Rewriting is legitimate here in a way it would not be for published content: a finding is live
 * state about a current subject, not a record of a past event. It reconciles, reopens on its
 * original row, and dies with its template.
 *
 * ### Why IMMEDIATE
 *
 * The opposite call from [OnVersionPublishedRunChecks]. A quality check is an observation about a
 * publish and must never roll one back. This is not an observation — it is part of what relocating
 * a resource *means*. If it cannot be applied the move must not commit, or the ledger is left
 * describing a template at an address that no longer exists.
 *
 * ### Why quality subscribes rather than core rewriting
 *
 * Core must never reach into quality; that direction is what keeps this module droppable. The move
 * command knows nothing about findings, and this handler is absent when the module is.
 */
@Component
class OnResourceRelocatedRepointFindings(
    private val jdbi: Jdbi,
) : EventHandler<MoveCatalogResources> {
    override val phase = EventPhase.IMMEDIATE

    override fun on(
        event: MoveCatalogResources,
        result: Any?,
    ) {
        val templates = event.relocations.filter { it.source.type == CatalogResourceType.TEMPLATE }
        if (templates.isEmpty()) return

        jdbi.useHandle<Exception> { handle ->
            for (relocation in templates) {
                // Segments are tenant/catalog/template. The address must end the URN or be followed
                // by '/': as a bare prefix, `letters/invoice` would also match `letters/invoice-v2`.
                repoint(
                    handle,
                    event.tenantKey,
                    old = "${event.tenantKey.value}/${relocation.source.catalogKey}/${relocation.source.key}",
                    new = "${event.tenantKey.value}/${relocation.target.catalogKey}/${relocation.target.key}",
                )
            }
        }
    }

    private fun repoint(handle: Handle, tenantKey: TenantKey, old: String, new: String) {
        // Every key is a lowercase slug and the separator is '/', so none of `old` needs escaping
        // in a regular expression; asserted rather than assumed.
        check(ADDRESS.matches(old)) { "Unexpected template address in a quality URN: $old" }
        val pattern = "^(urn:epistola:[a-z]+:)$old(/|$)"
        val replacement = "\\1$new\\2"
        handle.createUpdate(
            """
            UPDATE quality_findings
            SET subject_urn = regexp_replace(subject_urn, :pattern, :replacement),
                ignore_scope_urn = regexp_replace(ignore_scope_urn, :pattern, :replacement)
            WHERE tenant_key = :tenantKey
              AND (subject_urn ~ :pattern OR ignore_scope_urn ~ :pattern)
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("pattern", pattern)
            .bind("replacement", replacement)
            .execute()

        handle.createUpdate(
            """
            UPDATE quality_finding_ignores
            SET ignore_scope_urn = regexp_replace(ignore_scope_urn, :pattern, :replacement)
            WHERE tenant_key = :tenantKey AND ignore_scope_urn ~ :pattern
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("pattern", pattern)
            .bind("replacement", replacement)
            .execute()
    }

    private companion object {
        val ADDRESS = Regex("^[a-z0-9-]+/[a-z0-9-]+/[a-z0-9-]+$")
    }
}
