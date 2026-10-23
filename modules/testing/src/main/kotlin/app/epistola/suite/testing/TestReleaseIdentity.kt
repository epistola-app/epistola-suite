// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.catalog.canonical.CatalogCanonicalizer
import app.epistola.catalog.canonical.CatalogFingerprintVersion
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Mediator
import org.jdbi.v3.core.Jdbi
import java.io.ByteArrayInputStream

/**
 * Plays release [version] of [catalogKey] in [tenantKey] as one recorded before releases had a
 * content hash (ADR 0034): its fingerprint the V5 its content had, its content hash not computed
 * yet, as a release cut before V6 is until `ReleaseContentHashBackfill` reaches it. SQL, because no
 * command records a V5 fingerprint or leaves a release without its content hash any more. Returns
 * the V5 fingerprint.
 */
fun Mediator.recordBeforeContentHashes(jdbi: Jdbi, tenantKey: TenantKey, catalogKey: CatalogKey, version: String): String {
    val zip = send(ExportCatalogZip(tenantKey, catalogKey, version)).zipBytes
    val v5 = CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive!!.use {
        CatalogCanonicalizer.qualifiedFingerprint(it, CatalogFingerprintVersion.V5).toString()
    }
    jdbi.useHandle<Exception> { handle ->
        handle.createUpdate(
            """
            UPDATE catalog_releases r SET fingerprint = :f, content_hash = NULL,
                   manifest_snapshot = jsonb_set(r.manifest_snapshot, '{release,fingerprint}', to_jsonb(CAST(:f AS TEXT)))
            FROM catalogs c WHERE c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id AND r.tenant_key = :t AND c.catalog_id = epistola_catalog_id(:t, :c) AND r.version = :v
            """,
        ).bind("f", v5).bind("t", tenantKey).bind("c", catalogKey).bind("v", version).execute()
    }
    return v5
}
