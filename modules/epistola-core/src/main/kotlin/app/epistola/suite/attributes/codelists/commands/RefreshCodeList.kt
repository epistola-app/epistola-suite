package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.attributes.codelists.service.CodeListFetchException
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Re-fetches entries for a code list from its `source_url` and atomically
 * replaces the existing entries.
 *
 * Only valid for `URL` and `CLASSPATH` source types. INLINE lists are managed
 * via `UpdateCodeList`. On a fetch error the existing entries are kept and
 * `last_refresh_error` is updated; this means the picker keeps working even
 * when the source is temporarily unreachable.
 */
data class RefreshCodeList(
    val id: CodeListId,
) : Command<CodeList?>,
    RequiresPermission,
    // Fetches entries from the source URL mid-command, and a fetch error must be
    // recorded (committed) even though the refresh itself fails.
    SelfManagedTransaction {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey
}

class CodeListNotRefreshableException(message: String) : RuntimeException(message)

@Component
class RefreshCodeListHandler(
    private val jdbi: Jdbi,
    private val codeListClient: CodeListClient,
) : CommandHandler<RefreshCodeList, CodeList?> {
    override fun handle(command: RefreshCodeList): CodeList? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        val current = jdbi.withHandle<CodeList?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT slug, tenant_key, catalog_key, display_name, description,
                       source_type, source_url, auth_type, credential,
                       last_refreshed_at, last_refresh_error,
                       created_at, updated_at
                FROM code_lists
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                """,
            )
                .bind("tenantKey", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("slug", command.id.key)
                .mapTo<CodeList>()
                .findOne()
                .orElse(null)
        } ?: return null

        if (current.sourceType == CodeListSource.INLINE) {
            throw CodeListNotRefreshableException(
                "Code list '${command.id.key.value}' is INLINE — entries are edited directly, not refreshed from a source.",
            )
        }
        val sourceUrl = current.sourceUrl
            ?: throw CodeListNotRefreshableException("Code list '${command.id.key.value}' has no source URL")

        // Fetch outside the transaction — long-running HTTP shouldn't hold a DB
        // connection. On failure we still record the error so the UI can surface it.
        val fetched = try {
            codeListClient.fetchEntries(sourceUrl, current.authType, current.credential?.value)
        } catch (e: CodeListFetchException) {
            recordRefreshError(command.id, e.message ?: "fetch failed")
            return load(command.id)
        }

        try {
            validateCodeListEntries(fetched)
        } catch (e: ValidationException) {
            recordRefreshError(command.id, e.message)
            return load(command.id)
        }

        return jdbi.inTransaction<CodeList, Exception> { handle ->
            handle.createUpdate(
                """
                DELETE FROM code_list_entries
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND code_list_slug = :slug
                """,
            )
                .bind("tenantKey", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("slug", command.id.key)
                .execute()
            insertEntries(handle, command.id, fetched)

            handle.createQuery(
                """
                UPDATE code_lists
                SET last_refreshed_at  = NOW(),
                    last_refresh_error = NULL,
                    updated_at      = NOW()
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                RETURNING slug, tenant_key, catalog_key, display_name, description,
                          source_type, source_url, auth_type, credential,
                          last_refreshed_at, last_refresh_error,
                          created_at, updated_at
                """,
            )
                .bind("tenantKey", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("slug", command.id.key)
                .mapTo<CodeList>()
                .one()
        }
    }

    private fun recordRefreshError(id: CodeListId, message: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE code_lists
                SET last_refresh_error = :error, updated_at = NOW()
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                """,
            )
                .bind("tenantKey", id.tenantKey)
                .bind("catalogKey", id.catalogKey)
                .bind("slug", id.key)
                .bind("error", message)
                .execute()
        }
    }

    private fun load(id: CodeListId): CodeList? = jdbi.withHandle<CodeList?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT slug, tenant_key, catalog_key, display_name, description,
                   source_type, source_url, auth_type, credential,
                   last_refreshed_at, last_refresh_error,
                   created_at, updated_at
            FROM code_lists
            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
            """,
        )
            .bind("tenantKey", id.tenantKey)
            .bind("catalogKey", id.catalogKey)
            .bind("slug", id.key)
            .mapTo<CodeList>()
            .findOne()
            .orElse(null)
    }
}
