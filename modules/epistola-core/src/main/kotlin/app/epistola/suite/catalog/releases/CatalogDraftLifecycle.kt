// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import java.util.UUID

/**
 * What another domain does when a catalog's draft ends: told by the catalog command, on its handle,
 * so it happens in the same transaction. Catalog names no listener; environments is one, stopping
 * what served a discarded draft and logging a released one (`DraftDeploymentLifecycle`).
 */
interface CatalogDraftLifecycle {
    /** Draft [versionId] is discarded. */
    fun draftDiscarded(handle: Handle, tenantKey: TenantKey, versionId: UUID)

    /** [catalog] is deleted, and every draft of it with it. */
    fun catalogDeleted(handle: Handle, catalog: CatalogId)

    /**
     * Draft [versionId] is about to be released. Called before it is frozen; the step returned runs
     * once it is, with the version it was released as.
     */
    fun draftReleasing(handle: Handle, tenantKey: TenantKey, versionId: UUID): (version: String) -> Unit
}
