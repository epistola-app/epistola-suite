// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import org.jdbi.v3.core.Jdbi

/**
 * Ages a blob well past the content collector's grace period, so a sweep will consider it at all.
 *
 * Raw SQL by the rule's own exception — a specific historical timestamp the read path asserts
 * against, which no command can write because they all stamp `NOW()`.
 */
fun Jdbi.backdateAssetBlob(contentHash: String) = useHandle<Exception> { handle ->
    handle.createUpdate("UPDATE asset_content SET created_at = now() - interval '5 years' WHERE content_hash = :h")
        .bind("h", contentHash)
        .execute()
}
