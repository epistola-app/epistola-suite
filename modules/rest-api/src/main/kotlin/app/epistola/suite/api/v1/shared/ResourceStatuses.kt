// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ResourceStatus
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.queries.CatalogResourceChange
import app.epistola.suite.catalog.queries.CatalogResourceState
import app.epistola.suite.catalog.queries.GetCatalogResourceChanges
import app.epistola.suite.catalog.releases.ResolvedCatalogRelease
import app.epistola.suite.mediator.query

/**
 * The contract's [ResourceStatus] of the resources of one catalog version, against its base (contract
 * ADR 0002): what [GetCatalogResourceChanges] says changed since the last release, and whether the
 * change is marked ready.
 *
 * - unchanged since the last release: `released`;
 * - changed and marked ready: `ready`;
 * - otherwise `new` when the last release did not have it, else `modified`.
 *
 * Everything a released version holds is `released`, and so is a subscribed catalog's content: its
 * versions are the releases it installed.
 */
internal class ResourceStatuses private constructor(
    private val changes: Map<String, CatalogResourceChange>?,
) {
    /**
     * The status of the resource of [type] (`template`, `stencil`, …) at [key]. [ready] overrides the
     * resource's own readiness for a part of it, such as one variant of a template.
     */
    fun of(type: String, key: String, ready: Boolean? = null): ResourceStatus {
        if (changes == null) return ResourceStatus.RELEASED
        val change = changes["$type/$key"] ?: return if (ready == true) ResourceStatus.READY else ResourceStatus.NEW
        return when (change.state) {
            CatalogResourceState.RELEASED -> ResourceStatus.RELEASED

            CatalogResourceState.REMOVED -> ResourceStatus.NEW

            CatalogResourceState.NEW, CatalogResourceState.MODIFIED -> when {
                ready ?: change.ready -> ResourceStatus.READY
                change.state == CatalogResourceState.NEW -> ResourceStatus.NEW
                else -> ResourceStatus.MODIFIED
            }
        }
    }

    companion object {
        /** Reads the version's changes once; reuse the result for every resource on one response. */
        fun of(version: ResolvedCatalogRelease): ResourceStatuses {
            if (!version.isDraft) return ResourceStatuses(null)
            val changes = try {
                GetCatalogResourceChanges(version.catalogId, catalogVersion = version.ref).query()
            } catch (_: CatalogReadOnlyException) {
                return ResourceStatuses(null)
            }
            return ResourceStatuses(changes.resources.associateBy { "${it.type}/${it.slug}" })
        }
    }
}
