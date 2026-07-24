// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import java.time.OffsetDateTime

data class Tenant(
    val id: TenantKey,
    val name: String,
    val defaultThemeCatalogKey: CatalogKey? = CatalogKey.DEFAULT,
    val defaultThemeKey: ThemeKey?,
    val defaultLocale: String? = null,
    val createdAt: OffsetDateTime,
)
