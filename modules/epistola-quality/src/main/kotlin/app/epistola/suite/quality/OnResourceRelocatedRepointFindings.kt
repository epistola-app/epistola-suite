// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.relocation.MoveCatalogResource
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Repoints a moved template's findings and ignores at its new address.
 *
 * Findings carry `subject_urn` and ignores carry `ignore_scope_urn`, both built from
 * `EntityId.toUrn()` — which composes the resource's *address*
 * (`urn:epistola:template:tenantA/letters/invoice`). `ignore_scope_urn` is also the join condition
 * between a finding and its ignore, and part of the ignore's primary key.
 *
 * Without this, a move would leave ignores holding the old address while the next submission
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
 * publish and must never roll one back. This is not an observation — it is part of what moving a
 * resource *means*. If it cannot be applied the move must not commit, or the ledger is left
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
) : EventHandler<MoveCatalogResource> {
    override val phase = EventPhase.IMMEDIATE

    override fun on(
        event: MoveCatalogResource,
        result: Any?,
    ) {
        if (event.source.type != CatalogResourceType.TEMPLATE) return

        // Segments are tenant/catalog/template, so this prefix is unambiguous within a URN.
        val old = "${event.tenantKey.value}/${event.source.catalogKey}/${event.source.key}"
        val new = "${event.tenantKey.value}/${event.targetCatalogKey.value}/${event.source.key}"

        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE quality_findings
                SET subject_urn = REPLACE(subject_urn, :old, :new),
                    ignore_scope_urn = REPLACE(ignore_scope_urn, :old, :new)
                WHERE tenant_key = :tenantKey
                  AND (subject_urn LIKE '%' || :old || '%' OR ignore_scope_urn LIKE '%' || :old || '%')
                """,
            )
                .bind("tenantKey", event.tenantKey)
                .bind("old", old)
                .bind("new", new)
                .execute()

            handle.createUpdate(
                """
                UPDATE quality_finding_ignores
                SET ignore_scope_urn = REPLACE(ignore_scope_urn, :old, :new)
                WHERE tenant_key = :tenantKey AND ignore_scope_urn LIKE '%' || :old || '%'
                """,
            )
                .bind("tenantKey", event.tenantKey)
                .bind("old", old)
                .bind("new", new)
                .execute()
        }
    }
}
