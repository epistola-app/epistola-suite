// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** The catalog rows an import writes and the dependency probe it runs; callers own the transaction. */
@Component
class CatalogImportRows(
    private val jdbi: Jdbi,
) {
    fun touchImportedAt(tenantKey: TenantKey, catalogKey: CatalogKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate("UPDATE catalogs SET imported_at = NOW() WHERE tenant_key = :t AND catalog_key = :c")
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .execute()
        }
    }

    /** Inserts an empty SUBSCRIBED catalog row (no source URL — ZIP-sourced). */
    fun insertSubscribedCatalog(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        name: String,
        description: String?,
        sourceUrl: String?,
        registry: String,
        namespace: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalogs (catalog_key, tenant_key, registry, namespace, name, description, type, source_url, created_at, updated_at)
                VALUES (:c, :t, :registry, :namespace, :name, :description, 'SUBSCRIBED', :sourceUrl, NOW(), NOW())
                """,
            )
                .bind("c", catalogKey)
                .bind("registry", registry)
                .bind("namespace", namespace)
                .bind("t", tenantKey)
                .bind("name", name)
                .bind("description", description)
                .bind("sourceUrl", sourceUrl)
                .execute()
        }
    }

    /**
     * Advances the SUBSCRIBED install pointer from the imported ZIP — the same
     * `installed_*` state a URL [RegisterCatalog]/[UpgradeCatalog] records,
     * so the upgrade preview / drift detection work identically. Run last,
     * only on a fully successful import.
     */
    fun updateSubscribedInstalledState(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        version: String,
        fingerprint: String?,
        resourceFingerprintsJson: String,
        name: String,
        description: String?,
        catalogMetadataJson: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET installed_release_version = :version, installed_fingerprint = :fingerprint,
                    installed_resource_fingerprints = :resourceFingerprints::jsonb,
                    name = :name, description = :description, catalog_metadata = :catalogMetadata::jsonb,
                    installed_at = NOW(), content_updated_at = NOW(), updated_at = NOW()
                WHERE tenant_key = :t AND catalog_key = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("version", version)
                .bind("fingerprint", fingerprint)
                .bind("resourceFingerprints", resourceFingerprintsJson)
                .bind("name", name)
                .bind("description", description)
                .bind("catalogMetadata", catalogMetadataJson)
                .execute()
        }
    }

    fun updateAuthoredMetadata(
        tenantKey: TenantKey,
        catalogKey: CatalogKey,
        name: String,
        description: String?,
        catalogMetadataJson: String,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalogs
                SET name = :name, description = :description, catalog_metadata = :catalogMetadata::jsonb,
                    content_updated_at = NOW(), updated_at = NOW()
                WHERE tenant_key = :t AND catalog_key = :c
                """,
            )
                .bind("t", tenantKey)
                .bind("c", catalogKey)
                .bind("name", name)
                .bind("description", description)
                .bind("catalogMetadata", catalogMetadataJson)
                .execute()
        }
    }

    /**
     * Checks all dependencies in a single query per resource type.
     * Returns the list of dependencies that do NOT exist in the target system.
     */
    fun findMissingDependencies(
        tenantKey: TenantKey,
        deps: List<app.epistola.catalog.protocol.DependencyRef>,
    ): List<app.epistola.catalog.protocol.DependencyRef> {
        if (deps.isEmpty()) return emptyList()

        val found = mutableSetOf<String>() // unique key per dependency

        jdbi.withHandle<Unit, Exception> { handle ->
            // Catalog-scoped: themes
            val themeDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Theme>()
            if (themeDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, th.id FROM themes th JOIN catalogs rc ON rc.tenant_key = th.tenant_key AND rc.catalog_id = th.catalog_id WHERE th.tenant_key = :tenantKey AND ${inCurrentVersion("th")} AND th.id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", themeDeps.map { it.key })
                    .map { rs, _ -> "theme:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: stencils
            val stencilDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Stencil>()
            if (stencilDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, st.id FROM stencils st JOIN catalogs rc ON rc.tenant_key = st.tenant_key AND rc.catalog_id = st.catalog_id WHERE st.tenant_key = :tenantKey AND ${inCurrentVersion("st")} AND st.id IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", stencilDeps.map { it.key })
                    .map { rs, _ -> "stencil:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: images. This probe matched tenant-wide until wire v7, which was
            // only ever safe because an image's slug was a generated UUID -- two catalogs can both
            // hold a `logo`, and a match in the wrong one reported a dependency as satisfied that
            // was not. The dependency names its catalog now, so the probe can too.
            val imageDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Image>()
            if (imageDeps.isNotEmpty()) {
                handle.createQuery(
                    "SELECT rc.catalog_key, a.id::text AS id FROM assets a JOIN catalogs rc ON rc.tenant_key = a.tenant_key AND rc.catalog_id = a.catalog_id WHERE a.tenant_key = :tenantKey AND ${inCurrentVersion("a")} AND a.id::text IN (<slugs>)",
                )
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", imageDeps.map { it.key })
                    .map { rs, _ -> "image:${rs.getString("catalog_key")}:${rs.getString("id")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: code lists. Matches the same `catalog_key + slug`
            // shape as themes/stencils so the look-up cost is one PK probe.
            val codeListDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.CodeList>()
            if (codeListDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, cl.slug FROM code_lists cl JOIN catalogs rc ON rc.tenant_key = cl.tenant_key AND rc.catalog_id = cl.catalog_id WHERE cl.tenant_key = :tenantKey AND ${inCurrentVersion("cl")} AND cl.slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", codeListDeps.map { it.key })
                    .map { rs, _ -> "codeList:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }

            // Catalog-scoped: fonts. Same `catalog_key + slug` PK-probe shape
            // as themes/stencils/code lists.
            val fontDeps = deps.filterIsInstance<app.epistola.catalog.protocol.DependencyRef.Font>()
            if (fontDeps.isNotEmpty()) {
                handle.createQuery("SELECT rc.catalog_key, fo.slug FROM fonts fo JOIN catalogs rc ON rc.tenant_key = fo.tenant_key AND rc.catalog_id = fo.catalog_id WHERE fo.tenant_key = :tenantKey AND ${inCurrentVersion("fo")} AND fo.slug IN (<slugs>)")
                    .bind("tenantKey", tenantKey)
                    .bindList("slugs", fontDeps.map { it.key })
                    .map { rs, _ -> "font:${rs.getString("catalog_key")}:${rs.getString("slug")}" }
                    .list()
                    .let { found.addAll(it) }
            }
        }

        return deps.filter { dep ->
            when (dep) {
                is app.epistola.catalog.protocol.DependencyRef.Theme -> "theme:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Stencil -> "stencil:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Image -> "image:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.CodeList -> "codeList:${dep.catalogKey}:${dep.key}" !in found
                is app.epistola.catalog.protocol.DependencyRef.Font -> "font:${dep.catalogKey}:${dep.key}" !in found
            }
        }
    }
}
