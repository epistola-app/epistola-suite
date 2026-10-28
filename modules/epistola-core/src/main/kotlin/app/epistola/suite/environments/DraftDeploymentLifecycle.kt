// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.releases.CatalogDraftLifecycle
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.util.UUID

/** Environments serving a draft follow it: they stop when it is discarded and serve the release it becomes. */
@Component
class DraftDeploymentLifecycle : CatalogDraftLifecycle {
    override fun draftDiscarded(handle: Handle, tenantKey: TenantKey, versionId: UUID) = DraftDeploymentStore.endFor(handle, tenantKey, versionId)

    override fun catalogDeleted(handle: Handle, catalog: CatalogId) = DraftDeploymentStore.endForCatalog(handle, catalog)

    override fun draftReleasing(handle: Handle, tenantKey: TenantKey, versionId: UUID): (String) -> Unit {
        val served = DraftDeploymentStore.servingDraft(handle, tenantKey, versionId)
        return { version -> DraftDeploymentStore.released(handle, tenantKey, versionId, served, version) }
    }
}
