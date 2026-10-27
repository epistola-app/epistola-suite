// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.migration.preflight

import java.sql.Connection

/**
 * The references between catalogs in a 1.x database, as the 1.x-to-2.0 checks read them.
 *
 * After the upgrade an authored catalog's first release, its "Published in 1.x" draft released by
 * its author, pins the catalogs it uses, so they are released first; these are the uses that order
 * it. Read from what the upgrade keeps: each
 * variant's and stencil's latest published version and its draft, every theme, attribute definition
 * and template, and the catalogs' details. Only an authored catalog's uses count: a subscribed
 * catalog has its installed release recorded as it is, and waits for nothing.
 */
internal object OneXCatalogReferences {

    const val UPGRADE = "1.x to 2.0"

    /** One use of another catalog: [owner] (e.g. `template invoice`) in [catalogKey] uses [usedCatalog], through [via]. */
    data class Use(val tenantKey: String, val catalogKey: String, val owner: String, val usedCatalog: String, val via: String) {
        fun describe(): String = "$owner in '$catalogKey' uses '$usedCatalog' ($via)"
    }

    /** The tenant's catalogs, and which of them are authored. */
    data class Catalogs(val known: Map<String, Set<String>>, val authored: Map<String, Set<String>>)

    data class References(val catalogs: Catalogs, val uses: List<Use>)

    /**
     * Whether the database still has the 1.x shape this reads: the version tables, and templates
     * addressed by catalog key. A database already at 2.0, or new, has neither.
     */
    fun isOneX(context: UpgradePreflightContext): Boolean = context.shared("1x-shape") { connection ->
        connection.createStatement().use { st ->
            st.executeQuery(
                """
                SELECT to_regclass('template_versions') IS NOT NULL AND to_regclass('stencil_versions') IS NOT NULL
                   AND EXISTS (SELECT 1 FROM information_schema.columns
                               WHERE table_schema = current_schema() AND table_name = 'document_templates' AND column_name = 'catalog_key')
                """,
            ).use { rs -> rs.next() && rs.getBoolean(1) }
        }
    }

    /** The authored catalogs' uses of other catalogs, once per run. */
    fun of(context: UpgradePreflightContext): References = context.shared("1x-catalog-references", ::read)

    private fun read(connection: Connection): References {
        val known = mutableMapOf<String, MutableSet<String>>()
        val authored = mutableMapOf<String, MutableSet<String>>()
        connection.createStatement().use { st ->
            st.executeQuery("SELECT tenant_key, id, type FROM catalogs").use { rs ->
                while (rs.next()) {
                    known.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
                    if (rs.getString(3) == "AUTHORED") authored.getOrPut(rs.getString(1)) { mutableSetOf() } += rs.getString(2)
                }
            }
        }
        val uses = mutableListOf<Use>()
        connection.createStatement().use { st ->
            st.executeQuery(USES).use { rs ->
                while (rs.next()) {
                    val use = Use(rs.getString("tenant_key"), rs.getString("catalog_key"), rs.getString("owner"), rs.getString("used_catalog"), rs.getString("via"))
                    if (use.usedCatalog != use.catalogKey && use.catalogKey in authored[use.tenantKey].orEmpty()) uses += use
                }
            }
        }
        return References(Catalogs(known, authored), uses.distinct())
    }

    /**
     * Every use of another catalog in a 1.x database. A reference in content names its catalog as
     * `catalogKey` wherever it sits (a stencil instance, a theme override, an image, a font in theme
     * styles, an attribute in the catalog's details); a template's theme and an attribute's code list
     * are foreign keys; a variant's attributes are keyed `catalog.attribute`.
     */
    private val USES = """
        WITH kept_versions AS (
            SELECT DISTINCT ON (tv.tenant_key, tv.template_resource_id, tv.variant_key, tv.status)
                   tv.tenant_key, tv.template_resource_id, tv.variant_key, tv.template_model
            FROM template_versions tv
            WHERE tv.status IN ('draft', 'published')
            ORDER BY tv.tenant_key, tv.template_resource_id, tv.variant_key, tv.status, tv.id DESC
        ),
        kept_stencils AS (
            SELECT DISTINCT ON (sv.tenant_key, sv.stencil_resource_id, sv.status)
                   sv.tenant_key, sv.stencil_resource_id, sv.content
            FROM stencil_versions sv
            WHERE sv.status IN ('draft', 'published')
            ORDER BY sv.tenant_key, sv.stencil_resource_id, sv.status, sv.id DESC
        )
        SELECT dt.tenant_key, dt.catalog_key, 'template ' || dt.id || ' variant ' || kv.variant_key AS owner,
               ref #>> '{}' AS used_catalog, 'content' AS via
        FROM kept_versions kv
        JOIN document_templates dt ON dt.tenant_key = kv.tenant_key AND dt.resource_id = kv.template_resource_id
        CROSS JOIN LATERAL jsonb_path_query(kv.template_model, 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT s.tenant_key, s.catalog_key, 'stencil ' || s.id, ref #>> '{}', 'content'
        FROM kept_stencils ks
        JOIN stencils s ON s.tenant_key = ks.tenant_key AND s.resource_id = ks.stencil_resource_id
        CROSS JOIN LATERAL jsonb_path_query(ks.content, 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT th.tenant_key, th.catalog_key, 'theme ' || th.id, ref #>> '{}', 'styles'
        FROM themes th
        CROSS JOIN LATERAL jsonb_path_query(
            jsonb_build_array(th.document_styles, th.page_settings, th.block_style_presets), 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT c.tenant_key, c.id, 'catalog details', ref #>> '{}', 'attributes'
        FROM catalogs c
        CROSS JOIN LATERAL jsonb_path_query(COALESCE(c.catalog_metadata, '{}'::jsonb), 'strict $.**.catalogKey') AS ref
        WHERE jsonb_typeof(ref) = 'string'
        UNION ALL
        SELECT dt.tenant_key, dt.catalog_key, 'template ' || dt.id, r.catalog_key, 'theme ' || r.resource_key
        FROM document_templates dt
        JOIN catalog_resources r ON r.tenant_key = dt.tenant_key AND r.resource_id = dt.theme_resource_id
        UNION ALL
        SELECT ad.tenant_key, ad.catalog_key, 'attribute ' || ad.id, r.catalog_key, 'code list ' || r.resource_key
        FROM variant_attribute_definitions ad
        JOIN catalog_resources r ON r.tenant_key = ad.tenant_key AND r.resource_id = ad.code_list_resource_id
        UNION ALL
        SELECT dt.tenant_key, dt.catalog_key, 'template ' || dt.id || ' variant ' || v.id, split_part(attribute, '.', 1), 'attribute ' || attribute
        FROM template_variants v
        JOIN document_templates dt ON dt.tenant_key = v.tenant_key AND dt.resource_id = v.template_resource_id
        CROSS JOIN LATERAL jsonb_object_keys(COALESCE(v.attributes, '{}'::jsonb)) AS attribute
        WHERE position('.' IN attribute) > 0
    """
}
