// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveFeatureToggles
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission

/**
 * Whether a resource's page or row offers **Rename…** and **Move to another catalog…** (ADR 0042),
 * the one predicate the `fragments/refactoring` actions gate on, so every resource type answers it
 * alike.
 *
 * A resource is named by the catalog segment its page shows (`namespace/key`, with `@version` when
 * a version is open). The actions are offered when organising catalogs is on for the tenant, the
 * reader may manage catalogs, that catalog can be changed here, and what the page shows is a draft:
 * a release cannot be refactored. Toggles are read on the first question only.
 */
class RefactoringView(
    private val tenantKey: TenantKey,
    private val auth: AuthContext,
    private val editing: CatalogEditingView,
) {
    private val enabled: Boolean by lazy { ResolveFeatureToggles(tenantKey).query()[KnownFeatures.RESOURCE_RELOCATION] == true }

    /** Whether the actions are offered for a resource of the catalog [segment] names. */
    fun offered(segment: Any?): Boolean {
        val address = address(segment) ?: return false
        return auth.has(Permission.CATALOG_MANAGE) && enabled && editing.editable(address) && version(segment)?.let(DraftRef::parse) !is DraftRef.Released
    }

    /** The resource [key] of [type] in the catalog [segment] names, as the move dialog takes it (`type:namespace/catalog/key`). */
    fun resource(type: String, segment: Any?, key: Any?): String = "$type:${address(segment)}/$key"

    /** The id of the draft [segment] names, for the move to change it; empty for the next version. */
    fun draft(segment: Any?): String = (version(segment)?.let(DraftRef::parseDraft) as? DraftRef.Id)?.key.orEmpty()

    private fun address(segment: Any?): String? = segment?.toString()?.substringBefore('@')?.takeIf { '/' in it }

    private fun version(segment: Any?): String? = segment?.toString()?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }
}
