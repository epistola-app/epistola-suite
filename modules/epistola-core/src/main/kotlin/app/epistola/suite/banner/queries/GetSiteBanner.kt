// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.banner.queries

import app.epistola.suite.banner.SiteBanner
import app.epistola.suite.banner.SiteBannerStore
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import org.springframework.stereotype.Component

/**
 * Reads the stored site banner (enabled or not) to populate the admin edit page.
 * Gated to the platform [PlatformRole.TENANT_MANAGER]; the auth-bypassing sibling
 * used for rendering is [ResolveSiteBanner].
 */
class GetSiteBanner :
    Query<SiteBanner?>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
}

@Component
class GetSiteBannerHandler(
    private val store: SiteBannerStore,
) : QueryHandler<GetSiteBanner, SiteBanner?> {
    override fun handle(query: GetSiteBanner): SiteBanner? = store.get()
}
