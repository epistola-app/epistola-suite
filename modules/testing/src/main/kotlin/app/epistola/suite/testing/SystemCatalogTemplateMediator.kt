// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.catalog.system.InstallSystemCatalogResult
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_NAMESPACE
import app.epistola.suite.catalog.system.SystemCatalogStatus
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SystemUser
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Test-only: a new tenant's system catalog is copied from a template tenant, not installed again.
 *
 * Every tenant installs the bundled system catalog (`CreateTenant` → `InstallSystemCatalog`), and the
 * tests create a few thousand tenants: on CI that was most of the integration tests' time, the same
 * catalog received again and again. Here the catalog is installed once per database, for real, into
 * [TEMPLATE], and each new tenant gets a copy of its rows (about 180, in eight tables) under its own
 * tenant key.
 *
 * A [Mediator] in front of the real one: it answers [InstallSystemCatalog] for a tenant that has no
 * system catalog yet by copying, and passes everything else on, the template's own install included.
 *
 * PROTOTYPE: measured before it is kept.
 */
class SystemCatalogTemplateMediator(
    val delegate: Mediator,
    private val jdbi: Jdbi,
) : Mediator {

    @Suppress("UNCHECKED_CAST")
    override fun <R> send(command: Command<R>): R {
        if (command !is InstallSystemCatalog || command.tenantKey == TEMPLATE) return delegate.send(command)
        return (copied(command) ?: delegate.send(command)) as R
    }

    override fun <R> query(query: Query<R>): R = delegate.query(query)

    /** The template's system catalog copied to the command's tenant, or null when it has one already. */
    private fun copied(command: InstallSystemCatalog): InstallSystemCatalogResult? {
        val template = template()
        val copied = jdbi.inTransaction<Boolean, Exception> { handle ->
            if (hasSystemCatalog(handle, command.tenantKey)) return@inTransaction false
            copy(handle, template, command.tenantKey)
            true
        }
        return if (copied) InstallSystemCatalogResult(SystemCatalogStatus.INSTALLED, template.version) else null
    }

    private data class Template(val catalogId: UUID, val version: String)

    /** The template tenant's system catalog, installed for real the first time this database is asked. */
    private fun template(): Template {
        val database = jdbi.withHandle<String, Exception> { it.createQuery("SELECT current_database() || '@' || inet_server_port()").mapTo(String::class.java).one() }
        TEMPLATES[database]?.let { return it }
        synchronized(TEMPLATES) {
            TEMPLATES[database]?.let { return it }
            val found = find() ?: run {
                // Its own transaction, committed before any tenant copies from it: a thread of its own,
                // since this one is inside the transaction of the tenant being created.
                CREATOR.submit(MediatorContext.runnable(this, PRINCIPAL) { CreateTenant(id = TEMPLATE, name = "System catalog template").execute() }).get()
                checkNotNull(find()) { "The system catalog template was not installed" }
            }
            TEMPLATES[database] = found
            return found
        }
    }

    private fun find(): Template? = jdbi.withHandle<Template?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT c.catalog_id, v.version FROM catalogs c
            JOIN catalog_versions v ON v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id AND v.state = 'released'
            WHERE c.tenant_key = :t AND c.namespace = :ns AND c.catalog_key = :key
            """,
        ).bind("t", TEMPLATE.value).bind("ns", SYSTEM_CATALOG_NAMESPACE).bind("key", SYSTEM_CATALOG_KEY.value)
            .map { rs, _ -> Template(rs.getObject("catalog_id", UUID::class.java), rs.getString("version")) }
            .findOne().orElse(null)
    }

    private fun hasSystemCatalog(handle: Handle, tenant: TenantKey): Boolean = handle.createQuery(
        "SELECT EXISTS (SELECT 1 FROM catalogs WHERE tenant_key = :t AND namespace = :ns AND catalog_key = :key)",
    ).bind("t", tenant.value).bind("ns", SYSTEM_CATALOG_NAMESPACE).bind("key", SYSTEM_CATALOG_KEY.value).mapTo(Boolean::class.java).one()

    /**
     * Copies the template's system catalog to [tenant]: its rows as they are, under [tenant]'s key.
     * The ids stay the template's: every key of these tables includes the tenant key, so the same id
     * under another tenant is another row.
     */
    private fun copy(handle: Handle, template: Template, tenant: TenantKey) {
        // The rows are complete as they are: no trigger is to add to them or check them.
        handle.execute("SET LOCAL session_replication_role = replica")
        for ((table, filter) in TABLES) {
            val columns = columnsOf(handle, table)
            val select = columns.joinToString(", ") { if (it == "tenant_key") ":t" else "src.$it" }
            handle.createUpdate("INSERT INTO $table (${columns.joinToString(", ")}) SELECT $select FROM $table src WHERE src.tenant_key = :tpl AND $filter")
                .bind("t", tenant.value).bind("tpl", TEMPLATE.value).bind("c", template.catalogId).execute()
        }
        handle.execute("SET LOCAL session_replication_role = origin")
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

        /** The template's tables a tenant's system catalog has rows in, each with the rows of it to copy. */
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

        private val TEMPLATES = ConcurrentHashMap<String, Template>()
        private val COLUMNS = ConcurrentHashMap<String, List<String>>()
        private val CREATOR = Executors.newSingleThreadExecutor { Thread(it, "system-catalog-template").apply { isDaemon = true } }

        private val PRINCIPAL = EpistolaPrincipal(
            userId = SystemUser.ID,
            externalId = SystemUser.EXTERNAL_ID,
            email = SystemUser.EMAIL,
            displayName = SystemUser.DISPLAY_NAME,
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = null,
        )
    }
}
