// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A **fat export**: a catalog's release together with the releases of the catalogs it uses, so it
 * can be installed somewhere that has none of them (an acceptance or production installation that
 * receives catalogs as files).
 *
 * One ZIP holding a `bundle.json` index and, under `catalogs/`, one ordinary catalog archive per
 * catalog, exactly as a single export writes it. The index lists them in install order: the
 * catalogs used first (each before what uses it), the exported catalog last. A bundle holds one
 * release per catalog, the one the exported release is pinned to, followed transitively.
 */
data class CatalogBundle(
    /** The archives, in install order, the [root] last. */
    val entries: List<Entry>,
) {
    /** The exported catalog's archive. */
    val root: Entry get() = entries.last()

    /** The archives of the catalogs it uses, each before what uses it. */
    val dependencies: List<Entry> get() = entries.dropLast(1)

    /** One catalog's archive: its identity and release, and the archive bytes. */
    data class Entry(
        val registry: String,
        val namespace: String,
        val catalogKey: String,
        val version: String,
        val fingerprint: String?,
        val archive: ByteArray,
    ) {
        val identity: String get() = "$registry/$namespace/$catalogKey"

        /** Where it is stored in the bundle. */
        fun path(index: Int): String = "catalogs/%02d-%s-%s.zip".format(index + 1, catalogKey, version)

        override fun equals(other: Any?): Boolean = other is Entry && identity == other.identity && version == other.version && fingerprint == other.fingerprint && archive.contentEquals(other.archive)

        override fun hashCode(): Int = identity.hashCode() * 31 + version.hashCode()
    }

    fun write(objectMapper: ObjectMapper): ByteArray {
        val index = mapOf(
            "bundleVersion" to BUNDLE_VERSION,
            "catalogs" to entries.mapIndexed { i, e ->
                mapOf(
                    "registry" to e.registry,
                    "namespace" to e.namespace,
                    "catalogKey" to e.catalogKey,
                    "version" to e.version,
                    "fingerprint" to e.fingerprint,
                    "path" to e.path(i),
                )
            },
        )
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry(INDEX))
            zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(index))
            zip.closeEntry()
            entries.forEachIndexed { i, e ->
                zip.putNextEntry(ZipEntry(e.path(i)))
                zip.write(e.archive)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        const val INDEX: String = "bundle.json"
        const val BUNDLE_VERSION: Int = 1

        /** More catalogs than this is not a bundle anyone exported. */
        const val MAX_CATALOGS: Int = 100

        private val PATH = Regex("""^catalogs/[0-9]{2,3}-[a-z0-9][a-z0-9-]*-[0-9A-Za-z.+-]+\.zip$""")

        /** Whether [zip] is a bundle rather than a single catalog archive: it has a `bundle.json` at its root. */
        fun isBundle(zip: ByteArray): Boolean = runCatching {
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                generateSequence { input.nextEntry }.any { it.name == INDEX }
            }
        }.getOrDefault(false)

        /**
         * Reads a bundle, refusing anything that is not one as written by [write]: an unknown bundle
         * version, an entry the index does not name, a path outside `catalogs/`, more than
         * [MAX_CATALOGS] catalogs, or more than [maxExpandedBytes] once expanded.
         */
        fun read(zip: ByteArray, objectMapper: ObjectMapper, maxExpandedBytes: Long): CatalogBundle {
            val files = linkedMapOf<String, ByteArray>()
            var expanded = 0L
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    require(!entry.isDirectory) { "A catalog bundle holds no directories: '${entry.name}'" }
                    require(entry.name == INDEX || PATH.matches(entry.name)) { "Not a catalog bundle entry: '${entry.name}'" }
                    require(entry.name !in files) { "A catalog bundle names '${entry.name}' twice" }
                    require(files.size <= MAX_CATALOGS) { "A catalog bundle holds at most $MAX_CATALOGS catalogs" }
                    val bytes = input.readNBytes((maxExpandedBytes - expanded + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    expanded += bytes.size
                    require(expanded <= maxExpandedBytes) { "The catalog bundle is larger than $maxExpandedBytes bytes once expanded" }
                    files[entry.name] = bytes
                }
            }
            val index = objectMapper.readTree(requireNotNull(files[INDEX]) { "Not a catalog bundle: no $INDEX" })
            require(index.path("bundleVersion").asInt(-1) == BUNDLE_VERSION) {
                "Catalog bundle version ${index.path("bundleVersion")} is not supported; this installation reads version $BUNDLE_VERSION"
            }
            val catalogs = index.path("catalogs")
            require(catalogs.isArray && catalogs.size() > 0) { "A catalog bundle lists at least one catalog" }
            val entries = catalogs.values().map { node ->
                fun text(name: String) = node.path(name).takeIf { it.isString }?.asString() ?: throw IllegalArgumentException("A catalog bundle entry names no '$name'")
                val path = text("path")
                CatalogBundle.Entry(
                    registry = text("registry"),
                    namespace = text("namespace"),
                    catalogKey = text("catalogKey"),
                    version = text("version"),
                    fingerprint = node.path("fingerprint").takeIf { it.isString }?.asString(),
                    archive = requireNotNull(files[path]) { "The catalog bundle lists '$path', which it does not hold" },
                )
            }
            require(files.keys.minus(INDEX).all { path -> entries.indices.any { entries[it].path(it) == path } }) { "The catalog bundle holds an archive its index does not list" }
            require(entries.map { it.identity }.distinct().size == entries.size) { "A catalog bundle holds one release per catalog" }
            return CatalogBundle(entries)
        }
    }
}
