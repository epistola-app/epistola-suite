// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey

class StencilVersionNotDraftException(
    val tenantId: TenantKey,
    val stencilId: StencilKey,
    val catalogId: CatalogKey,
    val versionId: VersionKey,
) : RuntimeException("Stencil version ${versionId.value} of stencil ${stencilId.value} is not a draft")

class StencilVersionNotPublishedException(
    val tenantId: TenantKey,
    val stencilId: StencilKey,
    val catalogId: CatalogKey,
    val versionId: VersionKey,
) : RuntimeException("Stencil version ${versionId.value} of stencil ${stencilId.value} is not published")
