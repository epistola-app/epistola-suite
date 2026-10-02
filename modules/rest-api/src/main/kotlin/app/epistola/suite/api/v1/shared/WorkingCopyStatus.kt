// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ResourceStatus
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.queries.CatalogResourceChanges
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.query

/**
 * The contract's [ResourceStatus] for a resource of the working copy, derived from what the suite
 * stores today.
 *
 * Interim, until the working copy and its ready mark exist (WP3 of docs/catalog-release-model-v2.md).
 * A release holds each variant's and stencil's latest *published* version, so publishing a draft is
 * what marks it ready today:
 *
 * - a draft over nothing published is `new`; a draft over a published version is `modified`;
 * - no draft and nothing published is `new`;
 * - no draft, with the resource unchanged since the latest release, is `released`;
 * - no draft, with the published content not yet released, is `ready`.
 *
 * Whether published content was released is known per resource (a template, a stencil), not per
 * variant, so every variant of a template whose published content changed reads `ready`.
 */
internal class WorkingCopyStatus private constructor(
    private val states: Map<String, CatalogResourceState>?,
) {
    /** The status of a resource of [type] (`template`, `stencil`) with [slug]. */
    fun of(type: String, slug: String, hasDraft: Boolean, hasPublished: Boolean): ResourceStatus = when {
        hasDraft -> if (hasPublished) ResourceStatus.MODIFIED else ResourceStatus.NEW

        !hasPublished -> ResourceStatus.NEW

        // A subscribed catalog's content is the release it installed.
        states == null -> ResourceStatus.RELEASED

        states["$type/$slug"] == CatalogResourceState.RELEASED -> ResourceStatus.RELEASED

        else -> ResourceStatus.READY
    }

    companion object {
        /** Reads the catalog's changes once; reuse the result for every resource on one response. */
        fun of(tenantKey: TenantKey, catalogKey: CatalogKey): WorkingCopyStatus {
            val changes: CatalogResourceChanges? = try {
                GetCatalogResourceChanges(tenantKey, catalogKey).query()
            } catch (_: CatalogReadOnlyException) {
                null
            }
            return WorkingCopyStatus(changes?.resources?.associate { "${it.type}/${it.slug}" to it.state })
        }
    }
}
