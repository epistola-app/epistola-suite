// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi

/**
 * Makes a catalog's releases look like ones cut before they kept their content.
 *
 * Clears the flag as well as the rows, because the flag is the fact: a release that kept nothing is
 * not the same as one that contained nothing, and a catalog with no resources produces the second.
 * Removing only the rows would leave a release still claiming to be reproducible.
 *
 * Raw SQL, and no command can replace it: the shape only exists in a database upgraded from before
 * those migrations, so a command to produce it would be production code whose sole purpose is
 * creating obsolete data. Shared because four tests need the same historical state — the status
 * query's fallback, the assembler's refusal, the export's, and the Exchange publish path's.
 *
 * @return how many releases were made to forget, so a caller can assert it did something.
 */
fun Jdbi.forgetRetainedContent(tenantKey: TenantKey, catalogKey: CatalogKey): Int = withHandle<Int, Exception> { handle ->
    handle.createUpdate("DELETE FROM release_entries WHERE tenant_key = :t AND catalog_key = :c")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .execute()
    handle.createUpdate("UPDATE catalog_releases SET content_retained = FALSE WHERE tenant_key = :t AND catalog_key = :c")
        .bind("t", tenantKey)
        .bind("c", catalogKey)
        .execute()
}
