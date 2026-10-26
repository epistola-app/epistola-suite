// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import org.jdbi.v3.core.Jdbi
import java.io.ByteArrayInputStream

/**
 * Plays release [version] of [catalogKey] in [tenantKey] as one received with a V5 fingerprint (an
 * archive cut before V6, ADR 0034): its fingerprint the V5 its content had, its content hash recorded
 * as receiving records it. SQL, because no command here cuts a V5 release any more. Returns the V5
 * fingerprint.
 */
fun Mediator.recordBeforeContentHashes(jdbi: Jdbi, catalog: CatalogId, version: String): String {
    val zip = send(ExportCatalogZip(catalog, version)).zipBytes
    val v5 = CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive!!.use {
        CatalogCanonicalizer.qualifiedFingerprint(it, CatalogFingerprintVersion.V5).toString()
    }
    jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE catalog_versions r SET fingerprint = :f,
                   manifest_snapshot = jsonb_set(r.manifest_snapshot, '{release,fingerprint}', to_jsonb(CAST(:f AS TEXT)))
            FROM catalogs c WHERE r.state = 'released' AND c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id AND r.tenant_key = :t AND c.catalog_id = epistola_catalog_id(:t, :c_ns, :c) AND r.version = :v
            """,
        ).bind("f", v5).bind("t", catalog.tenantKey).bindCatalog("c", catalog).bind("v", version).execute()
    }
    return v5
}
