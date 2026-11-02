// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.sources

import com.github.benmanes.caffeine.cache.Caffeine

/**
 * The release archives of bundled catalogs (`classpath:` sources), built once per JVM.
 *
 * Every tenant installs the system catalog through the archive path every source takes (contract
 * ADR 0008): its release is written as an archive and imported. The classpath cannot change while
 * the JVM runs, so the archive of a bundled release is the same for every tenant, and is built once.
 * Handing every tenant the same array is also what lets the import read and verify it once
 * ([app.epistola.suite.catalog.commands.ImportArchiveReader]): the system catalog's fonts were
 * otherwise unzipped, verified against their hashes and hashed again for each tenant, which was
 * 80 % of installing it (profiled on 2 November).
 *
 * Bounded like [app.epistola.suite.catalog.ClasspathCatalogCache], by the bytes it holds: the system
 * catalog's archive is about 7 MB and every other bundled catalog's well under one, so the ceiling is
 * the bound said out loud, not something expected to evict. One per JVM, since a test JVM holds a
 * couple of dozen application contexts. Nothing writes into an archive once it is built.
 */
internal object BundledReleaseArchives {
    /** Ceiling, in archive bytes. */
    private const val MAX_BYTES: Long = 64L * 1024 * 1024

    private val archives = Caffeine.newBuilder()
        .maximumWeight(MAX_BYTES)
        .weigher<Pair<String, String>, ByteArray> { _, archive -> archive.size }
        .build<Pair<String, String>, ByteArray>()

    /** Whether [url] names a bundled catalog, whose content cannot change while the JVM runs. */
    fun isBundledSource(url: String?): Boolean = url != null && url.startsWith("classpath:")

    /** The archive of release [version] of the bundled catalog at [url], built by [build] the first time. */
    fun archive(url: String, version: String, build: () -> ByteArray): ByteArray = archives.get(url to version) { build() }

    /** Whether [archive] is one this cache handed out (by identity: a copy of one is not). */
    fun isBundled(archive: ByteArray): Boolean = archives.asMap().values.any { it === archive }
}
