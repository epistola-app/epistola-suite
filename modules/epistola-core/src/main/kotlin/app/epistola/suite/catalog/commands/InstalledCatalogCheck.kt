// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.FontResource
import app.epistola.catalog.protocol.ImageResource
import app.epistola.suite.catalog.CatalogContent
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.springframework.stereotype.Component

/**
 * Holds an installed catalog to every rule a valid catalog keeps, as a ZIP import is held
 * ([ImportCatalogZip]). Without it a catalog installed from a URL could break a rule — reference a
 * catalog it does not declare — and be refused only later, on export or release.
 *
 * Reads the catalog back as it is installed, so it runs inside the install's transaction, where a
 * refusal leaves nothing behind, once the catalog's content is complete.
 */
@Component
class InstalledCatalogCheck(
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
) {
    /**
     * [fetched] are the binaries the install holds already, by the hash their resources declare. The
     * check reads each binary the stored catalog links from those rather than back from the database
     * (a font's faces are megabytes; the system fonts are 14 MB, on every tenant's creation), and
     * still hashes it against what the stored catalog declares. A binary the stored catalog does not
     * link is still missing, whatever the install fetched.
     */
    fun requireValid(catalog: CatalogId, fetched: Map<String, ByteArray> = emptyMap()) {
        val content = contentBuilder.build(catalog)
        val report = fingerprintService.publishability(if (fetched.isEmpty()) content else content.copy(assetContents = FetchedFirst(content, fetched)))
        if (!report.valid) {
            throw CatalogImportValidationException(
                report.findings.map { CatalogImportValidationFinding(it.code, it.severity, it.path, it.message) },
            )
        }
    }
}

/** [content]'s binaries, each one the install [fetched] taken from there, by the hash its resource declares. */
private class FetchedFirst(content: CatalogContent, private val fetched: Map<String, ByteArray>) : AbstractMap<String, ByteArray>() {
    private val stored = content.assetContents
    private val hashByPath: Map<String, String> = content.resourceDetails.values.flatMap { detail ->
        when (val resource = detail.resource) {
            is ImageResource -> listOf(resource.contentPath() to resource.contentHash)
            is FontResource -> resource.variants.map { it.contentPath() to it.contentHash }
            else -> emptyList()
        }
    }.toMap()

    override fun get(key: String): ByteArray? = if (!stored.containsKey(key)) null else hashByPath[key]?.let(fetched::get) ?: stored[key]

    override fun containsKey(key: String): Boolean = stored.containsKey(key)

    override val entries: Set<Map.Entry<String, ByteArray>>
        get() = stored.keys.mapNotNull { key -> get(key)?.let { java.util.AbstractMap.SimpleImmutableEntry(key, it) } }.toSet()
}
