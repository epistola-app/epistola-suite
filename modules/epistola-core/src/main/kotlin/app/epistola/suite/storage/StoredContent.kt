// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.storage

import java.io.InputStream

/**
 * Binary content read back from a store ([AssetContentStore], [DocumentContentStore]).
 */
data class StoredContent(
    val content: InputStream,
    val contentType: String,
    val sizeBytes: Long,
)
