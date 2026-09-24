// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi

/**
 * Makes a catalog's releases look like ones cut before `V20260923201010`, when a release began
 * recording the resources it contained.
 *
 * Raw SQL, and no command can replace it: the shape only exists in a database upgraded from before
 * that migration, so a command to produce it would be production code whose sole purpose is
 * creating obsolete data. Shared because three tests need the same historical state — the status
 * query's fallback, the assembler's refusal, and the export's.
 *
 * @return how many entries were forgotten, so a caller can assert it actually did something.
 */
fun Jdbi.forgetReleaseEntries(tenantKey: TenantKey, catalogKey: CatalogKey): Int = withHandle<Int, Exception> { handle ->
    handle.createUpdate("DELETE FROM release_entries WHERE tenant_key = :t AND catalog_key = :c")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .execute()
}
