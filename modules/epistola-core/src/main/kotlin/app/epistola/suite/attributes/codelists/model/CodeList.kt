// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.model

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.crypto.Secret
import org.jdbi.v3.core.mapper.reflect.ColumnName
import java.time.OffsetDateTime

/**
 * A named collection of {code, label} entries that an attribute can bind to as
 * its value constraint. Tenant + catalog scoped, mirroring the attributes that
 * reference it.
 *
 * Entries are loaded separately via `ListCodeListEntries` to avoid hauling
 * up to a few hundred rows around when the UI just lists code lists.
 */
data class CodeList(
    val slug: CodeListKey,
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val displayName: String,
    val description: String? = null,
    val sourceType: CodeListSource,
    val sourceUrl: String? = null,
    val authType: AuthType = AuthType.NONE,
    /** Encrypted at rest via the JDBI [Secret] mappers; plaintext only in memory. */
    val credential: Secret? = null,
    val lastRefreshedAt: OffsetDateTime? = null,
    val lastRefreshError: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    /** Raised by every change; see [app.epistola.suite.common.Revisions]. */
    val revision: Long = 1,
)
