// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
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
    fun requireValid(tenantKey: TenantKey, catalogKey: CatalogKey) {
        val report = fingerprintService.publishability(contentBuilder.build(tenantKey, catalogKey))
        if (!report.valid) {
            throw CatalogImportValidationException(
                report.findings.map { CatalogImportValidationFinding(it.code, it.severity, it.path, it.message) },
            )
        }
    }
}
