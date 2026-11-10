// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_NAMESPACE
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** How a test tenant gets its system catalog. */
enum class SystemCatalogSetup {
    /** Copied from [SystemCatalogTemplate]'s template tenant: what nearly every test needs, fast. */
    FROM_TEMPLATE,

    /** Installed for real, as production does: for the tests that are about installing it. */
    INSTALL,
}

/**
 * Creates test tenants whose system catalog is copied from a template tenant, not installed again.
 *
 * Every tenant gets the bundled system catalog, and the tests create a few thousand tenants: installed
 * for each one (about 17 resources through the whole import path), that was most of the integration
 * tests' time on CI. So the catalog is installed once per database, for real, into [TEMPLATE], from
 * the same bundled catalog every tenant gets; a test tenant is created without it
 * ([CreateTenant.installSystemCatalog] false) and then gets a copy of the template's rows (about 180,
 * in nine tables) under its own tenant key.
 *
 * The ids in the copy stay the template's: every key of those tables includes the tenant key, so the
 * same id under another tenant is another row.
 *
 * What this gives up (shared ids, the install's commands not sent, the install path run by few
 * tests, triggers off for the copy) and how each shows when a test misbehaves: docs/testing.md,
 * "Test tenants and the system catalog template".
 */
@Component
class SystemCatalogTemplate(private val jdbi: Jdbi, private val usedCatalogs: UsedCatalogDeclarer) {

    /**
     * Runs [command] for a tenant whose system catalog comes as [setup] says. Needs a mediator and a
     * principal that may create tenants bound (the test helpers bind both), and no open transaction.
     */
    fun createTenant(command: CreateTenant, setup: SystemCatalogSetup = SystemCatalogSetup.FROM_TEMPLATE): Tenant {
        if (setup == SystemCatalogSetup.INSTALL) return command.execute()
        val template = template()
        val tenant = command.copy(installSystemCatalog = false).execute()
        copy(template, tenant.id)
        // As CreateTenant does once it installed the system catalog: the default catalog declares it.
        usedCatalogs.declareSystemCatalog(defaultCatalog(tenant.id))
        return tenant
    }

    private fun defaultCatalog(tenant: TenantKey): CatalogId = jdbi.withHandle<CatalogId, Exception> { handle ->
        handle.createQuery("SELECT namespace FROM catalogs WHERE tenant_key = :t AND catalog_key = :key")
            .bind("t", tenant.value).bind("key", CatalogKey.DEFAULT.value)
            .mapTo(String::class.java).one()
            .let { CatalogId(it, CatalogKey.DEFAULT, TenantId(tenant)) }
    }

    private data class Template(val catalogId: UUID)

    /** This database's template, installed for real the first time it is asked for. */
    private fun template(): Template {
        val database = jdbi.withHandle<String, Exception> { it.createQuery("SELECT current_database() || '@' || inet_server_port()").mapTo(String::class.java).one() }
        TEMPLATES[database]?.let { return it }
        synchronized(TEMPLATES) {
            TEMPLATES[database]?.let { return it }
            val template = find() ?: run {
                CreateTenant(id = TEMPLATE, name = "System catalog template").execute()
                checkNotNull(find()) { "The system catalog template was not installed" }
            }
            TEMPLATES[database] = template
            return template
        }
    }

    private fun find(): Template? = jdbi.withHandle<Template?, Exception> { handle ->
        handle.createQuery("SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND namespace = :ns AND catalog_key = :key")
            .bind("t", TEMPLATE.value).bind("ns", SYSTEM_CATALOG_NAMESPACE).bind("key", SYSTEM_CATALOG_KEY.value)
            .mapTo(UUID::class.java).findOne().orElse(null)?.let(::Template)
    }

    /** Copies the template's system catalog to [tenant], in one transaction: its rows as they are, under [tenant]'s key. */
    private fun copy(template: Template, tenant: TenantKey) = jdbi.useTransaction<Exception> { handle ->
        // The rows are complete as they are: no trigger is to add to them or check them.
        handle.execute("SET LOCAL session_replication_role = replica")
        for ((table, filter) in TABLES) {
            val columns = columnsOf(handle, table)
            val select = columns.joinToString(", ") { if (it == "tenant_key") ":t" else "src.$it" }
            handle.createUpdate("INSERT INTO $table (${columns.joinToString(", ")}) SELECT $select FROM $table src WHERE src.tenant_key = :tpl AND $filter")
                .bind("t", tenant.value).bind("tpl", TEMPLATE.value).bind("c", template.catalogId).execute()
        }
    }

    private fun columnsOf(handle: Handle, table: String): List<String> = COLUMNS.computeIfAbsent(table) {
        handle.createQuery(
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = :table AND is_generated = 'NEVER'
            ORDER BY ordinal_position
            """,
        ).bind("table", table).mapTo(String::class.java).list()
    }

    companion object {
        /** The tenant the system catalog is installed into for real, once per database. */
        val TEMPLATE: TenantKey = TenantKey.of("system-catalog-template")

        /** The tables a tenant's system catalog has rows in, each with which of the template's rows to copy. */
        private val TABLES = listOf(
            "catalogs" to "src.catalog_id = :c",
            "catalog_versions" to "src.catalog_id = :c",
            "catalog_resources" to "src.catalog_id = :c",
            "catalog_version_dependencies" to "src.catalog_id = :c",
            "release_resources" to "src.catalog_id = :c",
            "resource_ready_marks" to "src.version_id IN (SELECT version_id FROM catalog_versions WHERE tenant_key = :tpl AND catalog_id = :c)",
            // Contents are the tenant's, not a catalog's; the template's default catalog holds none.
            "resource_contents" to "TRUE",
            "resource_references" to "TRUE",
            "resource_binaries" to "TRUE",
        )

        /** Per database (a test JVM may run contexts against more than one), shared by its contexts. */
        private val TEMPLATES = ConcurrentHashMap<String, Template>()
        private val COLUMNS = ConcurrentHashMap<String, List<String>>()
    }
}
