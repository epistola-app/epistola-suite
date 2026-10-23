// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.springframework.stereotype.Component

/**
 * The bytes a release of the tenant holds under [contentHash] (an image or a font face, as a
 * released resource names it), or null when no retained release holds them. Only what a release
 * retained answers: an asset of the working copy with the same hash does not.
 */
data class GetReleasedBinary(
    override val tenantKey: TenantKey,
    val contentHash: String,
) : Query<ByteArray?>,
    RequiresPermission {
    // As for the working copy's image content (`GetAssetContent`).
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetReleasedBinaryHandler(
    private val assembler: ReleaseContentAssembler,
) : QueryHandler<GetReleasedBinary, ByteArray?> {
    override fun handle(query: GetReleasedBinary): ByteArray? = assembler.readBinary(query.tenantKey, query.contentHash)
}
