// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.canonical.CatalogCanonicalizer
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * [zip], a wire-v8 archive a test wrote by hand, with its release named by its own fingerprint, as a
 * producer writes one: wire v8 requires it (contract `2.0.0-alpha.4`), and it is what an import
 * checks the content against.
 */
fun withReleaseFingerprint(zip: ByteArray): ByteArray {
    val fingerprint = releaseFingerprintOf(zip)
    val mapper = JsonMapper.builder().build()
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { target ->
        ZipInputStream(ByteArrayInputStream(zip)).use { source ->
            generateSequence { source.nextEntry }.forEach { entry ->
                var bytes = source.readAllBytes()
                if (entry.name == "catalog.json") {
                    val manifest = mapper.readTree(bytes) as ObjectNode
                    (manifest.get("release") as ObjectNode).put("fingerprint", fingerprint)
                    bytes = mapper.writeValueAsBytes(manifest)
                }
                target.putNextEntry(ZipEntry(entry.name))
                target.write(bytes)
                target.closeEntry()
            }
        }
    }
    return out.toByteArray()
}

/** The release fingerprint the catalog archive [zip] has, as a producer computes it. */
fun releaseFingerprintOf(zip: ByteArray): String = checkNotNull(CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive) { "Not a readable catalog archive" }
    .use { CatalogCanonicalizer.currentQualifiedFingerprint(it).toString() }

/** A zip of [entries], each path to its bytes. */
fun zipOf(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
}.toByteArray()
