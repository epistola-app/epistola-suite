package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.crypto.Secret
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Creates a code list in an AUTHORED catalog.
 *
 * For `INLINE` source the caller passes initial entries directly; for `URL`
 * the caller passes a URL plus optional auth and `entries` is empty (entries
 * are populated on first refresh via `RefreshCodeList`). `CLASSPATH` is for
 * future bundled-content imports — not exposed in the user-facing CRUD.
 */
data class CreateCodeList(
    val id: CodeListId,
    val displayName: String,
    val description: String? = null,
    val sourceType: CodeListSource,
    val sourceUrl: String? = null,
    val authType: AuthType = AuthType.NONE,
    val credential: String? = null,
    val entries: List<CodeListEntry> = emptyList(),
) : Command<CodeList>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey

    init {
        validate("displayName", displayName.isNotBlank()) { "Display name is required" }
        validate("displayName", displayName.length <= 100) { "Display name must be 100 characters or less" }
        when (sourceType) {
            CodeListSource.INLINE -> {
                validate("sourceUrl", sourceUrl == null) { "Inline code lists must not have a source URL" }
                validate("entries", entries.isNotEmpty()) { "Inline code lists must have at least one entry" }
            }
            CodeListSource.URL, CodeListSource.CLASSPATH -> {
                validate("sourceUrl", !sourceUrl.isNullOrBlank()) { "Source URL is required for ${sourceType.name} code lists" }
            }
        }
        validate("entries", entries.all { it.code.isNotBlank() }) { "Entry codes must not be blank" }
        validate("entries", entries.all { it.label.isNotBlank() }) { "Entry labels must not be blank" }
        validate("entries", entries.map { it.code }.toSet().size == entries.size) { "Entry codes must be unique within a code list" }
    }
}

@Component
class CreateCodeListHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateCodeList, CodeList> {
    override fun handle(command: CreateCodeList): CodeList {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return executeOrThrowDuplicate("code-list", command.id.key.value) {
            jdbi.inTransaction<CodeList, Exception> { handle ->
                val inserted = handle.createQuery(
                    """
                    INSERT INTO code_lists (slug, tenant_key, catalog_key, display_name, description,
                                            source_type, source_url, auth_type, credential,
                                            created_at, updated_at)
                    VALUES (:slug, :tenantKey, :catalogKey, :displayName, :description,
                            :sourceType, :sourceUrl, :authType, :credential,
                            NOW(), NOW())
                    RETURNING slug, tenant_key, catalog_key, display_name, description,
                              source_type, source_url, auth_type, credential,
                              last_refreshed_at, last_refresh_error,
                              created_at, updated_at
                    """,
                )
                    .bind("slug", command.id.key)
                    .bind("tenantKey", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("displayName", command.displayName)
                    .bind("description", command.description)
                    .bind("sourceType", command.sourceType.name)
                    .bind("sourceUrl", command.sourceUrl)
                    .bind("authType", command.authType.name)
                    .bind("credential", command.credential?.let(::Secret))
                    .mapTo<CodeList>()
                    .one()

                if (command.entries.isNotEmpty()) {
                    insertEntries(handle, command.id, command.entries)
                }
                inserted
            }
        }
    }
}
