// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation

import app.epistola.generation.pdf.FontProgramCache
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * Bounds of the parsed-font cache every render on this node shares ([FontProgramCache]). Faces are
 * evicted to stay within both limits.
 *
 * A parsed face takes far more heap than its file, and how much more varies by font: a 400 KB Inter
 * face holds about 25 MB, a 270 KB JetBrains Mono face about 1 MB. [maxSize] is measured as the
 * memory parsing a face allocated, an upper bound on what it keeps.
 */
@ConfigurationProperties(prefix = "epistola.generation.font-cache")
data class FontCacheProperties(
    /** Estimated heap the cached faces may hold together. */
    val maxSize: DataSize = DataSize.ofBytes(FontProgramCache.DEFAULT_MAX_BYTES),
    /** Number of faces kept. `0` turns the cache off: every render parses its fonts again. */
    val maxFaces: Int = FontProgramCache.DEFAULT_MAX_FACES,
)
