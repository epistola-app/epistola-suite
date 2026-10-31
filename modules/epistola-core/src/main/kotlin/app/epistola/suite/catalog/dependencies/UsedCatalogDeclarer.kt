// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.dependencies

import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.catalog.CatalogEditingProperties
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.queries.CatalogEditability
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Declares a catalog the moment a write starts using it, so a draft's declarations always say
 * which other catalogs it uses (contract ADR 0003) and every surface declares the same way.
 *
 * A write that references a resource of another catalog the current version (the draft the
 * running message targets) does not declare yet declares that catalog: one that can be changed
 * here by naming its next version, which is what the draft then renders it from
 * ([DraftDependencyView]); one that cannot (Epistola supplies it, or the installation is run-only,
 * ADR 0032) by pinning its highest release. Releasing pins a named draft once that draft is
 * released. A declaration already there is left as the author set it, and a reference to a catalog
 * the tenant does not have is left undeclared: the write is refused when it commits
 * ([app.epistola.suite.catalog.revisions.DraftReferenceGuard]).
 *
 * A reference names its catalog by alias, which is the catalog's key here (ADR 0034, section 9). An
 * alias the catalog already declares means that declaration; an undeclared one is declared as the
 * tenant's catalog with that key, and refused when two catalogs share it: the writer then declares
 * the one it means first ([declare] with the target's [CatalogId]).
 */
@Component
class UsedCatalogDeclarer(
    private val jdbi: Jdbi,
    private val store: CatalogDependencyStore,
    private val editing: CatalogEditingProperties,
) {
    /** [declareAliases] for the catalogs [references] point into. @return the declarations added. */
    fun declare(catalog: CatalogId, references: Collection<DependencyRef>): List<CatalogDependencyDeclaration> = declareAliases(catalog, references.map { CatalogKey.of(it.catalogKey) })

    /**
     * [declareAliases] for the catalogs a variant's [attributes] name: an attribute of another catalog
     * is keyed `<alias>.<key>` (`system.locale`, contract ADR 0003).
     */
    fun declareAttributes(catalog: CatalogId, attributes: Collection<String>): List<CatalogDependencyDeclaration> = declareAliases(catalog, attributeCatalogs(attributes))

    /** Declares [used], a catalog a direct reference names (a template's theme, an attribute's code list), unless [catalog] declares it. */
    fun declare(catalog: CatalogId, used: CatalogId?): List<CatalogDependencyDeclaration> {
        if (used == null || used == catalog) return emptyList()
        return jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
            val declared = store.declarations(handle, catalog)
            if (declared.any { it.namespace == used.namespace && it.catalogKey == used.key.value }) return@inTransaction emptyList()
            val added = handle.createQuery(
                "SELECT catalog_key, registry, namespace, COALESCE(source_url LIKE 'classpath:%', FALSE) AS supplied FROM catalogs WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}",
            )
                .bind("t", catalog.tenantKey)
                .bindCatalog("c", used)
                .map(::row)
                .list()
                .map { pinOrName(handle, catalog, it) }
            if (added.isNotEmpty()) store.replace(handle, catalog, declared + added)
            added
        }
    }

    /** Declares each alias of [aliases] that [catalog] does not declare yet, itself excepted. @return the declarations added. */
    fun declareAliases(catalog: CatalogId, aliases: Collection<CatalogKey>): List<CatalogDependencyDeclaration> {
        val used = aliases.filter { it != catalog.key }.map { it.value }.toSet()
        if (used.isEmpty()) return emptyList()
        return jdbi.inTransaction<List<CatalogDependencyDeclaration>, Exception> { handle ->
            val declared = store.declarations(handle, catalog)
            val missing = used.filter { alias -> declared.none { it.alias == alias } }
            if (missing.isEmpty()) return@inTransaction emptyList()
            val found = handle.createQuery(
                "SELECT catalog_key, registry, namespace, COALESCE(source_url LIKE 'classpath:%', FALSE) AS supplied FROM catalogs WHERE tenant_key = :t AND catalog_key IN (<keys>) ORDER BY catalog_key, namespace",
            )
                .bind("t", catalog.tenantKey)
                .bindList("keys", missing)
                .map(::row)
                .list()
            found.groupBy { it.first.catalogKey }.filterValues { it.size > 1 }.entries.firstOrNull()?.let { (key, several) ->
                throw ValidationException(
                    "catalogKey",
                    "Several catalogs of this tenant are called $key (${several.joinToString { "${it.first.namespace}/$key" }}): declare the one this catalog uses first.",
                    ValidationCode.CATALOG_KEY_AMBIGUOUS,
                )
            }
            val added = found.map { pinOrName(handle, catalog, it) }
            if (added.isNotEmpty()) store.replace(handle, catalog, declared + added)
            added
        }
    }

    private fun row(rs: java.sql.ResultSet, ctx: org.jdbi.v3.core.statement.StatementContext) = Pair(CatalogDependencyDeclaration(rs.getString("registry"), rs.getString("namespace"), rs.getString("catalog_key")), rs.getBoolean("supplied"))

    /** A catalog that can be changed here by naming its next version, else (ADR 0032) by pinning its highest release. */
    private fun pinOrName(handle: org.jdbi.v3.core.Handle, catalog: CatalogId, found: Pair<CatalogDependencyDeclaration, Boolean>): CatalogDependencyDeclaration {
        val (declaration, supplied) = found
        return if (CatalogEditability.of(supplied, editing.enabled).editable) {
            store.nextVersionOf(handle, catalog.tenantKey, declaration)?.let { (key, label) -> declaration.copy(draftId = key, draftName = label) }
        } else {
            store.highest(handle, catalog.tenantKey, declaration)?.let { declaration.copy(releaseVersion = it.version, releaseFingerprint = it.fingerprint) }
        } ?: declaration
    }
}

/** The catalogs variant attribute keys name by their qualifier (`system.locale` names `system`). */
fun attributeCatalogs(attributes: Collection<String>): List<CatalogKey> = attributes.mapNotNull { key ->
    key.substringBefore('.', "").takeIf { it.isNotEmpty() }?.let { runCatching { CatalogKey.of(it) }.getOrNull() }
}.distinct()
