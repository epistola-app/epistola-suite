// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import java.util.UUID

/**
 * How resources of one type are relocated (an alpha feature), said by the domain that owns the type
 * (ADR 0039). The relocation planner decides what moves and what must be rewritten; each step is
 * carried out by the relocator of the type it concerns, so the move handler names no type.
 *
 * Kept apart from [app.epistola.suite.catalog.types.ResourceType] because it writes through the
 * domain's store, and the content store already reads the types.
 */
interface ResourceRelocator {
    /** The wire name of the type it relocates. */
    val type: String

    /**
     * Moves [key] of [source]'s targeted draft to [targetKey] in [target]'s current version, its
     * content renamed as the type requires; false when [source] holds no such resource.
     */
    fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean

    /**
     * Applies [rewrite], a step the planner made for a resource of this type, when its content is
     * still what the plan saw: 1 when it applied, else 0 (the plan is stale). A type the planner makes
     * no rewrites for keeps the default.
     */
    fun rewrite(handle: Handle, tenantKey: TenantKey, rewrite: JsonRewrite): Int = 0

    /**
     * Before [moved] (a resource of any type, with identity [resourceId]) moves: points what this
     * type holds that names it by alias and key at its destination, while it can still be found.
     */
    fun beforeMove(handle: Handle, tenantKey: TenantKey, moved: ResourceRelocationPlan, resourceId: UUID) = Unit
}
