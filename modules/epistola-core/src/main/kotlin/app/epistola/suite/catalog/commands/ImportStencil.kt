package app.epistola.suite.catalog.commands

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Installs one stencil-version row carried in a catalog ZIP. The stencil's
 * `version` is taken from the wire format (no longer renumbered to `MAX+1`)
 * so that templates pinning a specific version survive a round-trip.
 *
 * Idempotency / conflict semantics:
 *  - target has no row for (slug, version) → insert at the given version
 *  - target has (slug, version) and content is **byte-identical** (JSONB `=`)
 *    → no-op, returns `SKIPPED` with `assignedVersion = version`
 *  - target has (slug, version) but content **differs** → behaviour depends on
 *    [ImportStencil.onConflict]:
 *      - `FAIL` (default) — throws [StencilVersionConflictException]; the
 *        orchestrator collects all conflicts in one pass
 *      - `RENUMBER` — inserts as `MAX(version)+1`, returns the new number;
 *        the orchestrator uses this to rewrite stencil-node pins in the
 *        templates from the same ZIP
 */
data class ImportStencil(
    val tenantId: TenantId,
    val catalogKey: CatalogKey = CatalogKey.DEFAULT,
    val slug: String,
    val version: Int,
    val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
    val parameterSchema: Map<String, Any?>? = null,
    val onConflict: OnStencilConflict = OnStencilConflict.FAIL,
) : Command<ImportStencilResult>,
    RequiresPermission {
    override val permission get() = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = tenantId.key
}

/**
 * Outcome of a single [ImportStencil] invocation. [assignedVersion] is the
 * version actually written to the target — equal to [ImportStencil.version]
 * on the happy path, set to a fresh `MAX+1` only when [OnStencilConflict.RENUMBER]
 * resolved a content conflict.
 */
data class ImportStencilResult(
    val status: InstallStatus,
    val assignedVersion: Int,
    val wasRenumbered: Boolean,
)

@Component
class ImportStencilHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<ImportStencil, ImportStencilResult> {

    override fun handle(command: ImportStencil): ImportStencilResult {
        val stencilKey = StencilKey.of(command.slug)
        val tagsJson = objectMapper.writeValueAsString(command.tags)
        val contentJson = objectMapper.writeValueAsString(command.content)
        val parameterSchemaJson = command.parameterSchema?.let { objectMapper.writeValueAsString(it) }
        val auditUser = currentUserIdOrNull()?.value

        return jdbi.inTransaction<ImportStencilResult, Exception> { handle ->
            val stencilRowExisted = handle.createQuery("SELECT COUNT(*) > 0 FROM stencils WHERE id = :id AND tenant_key = :tenantKey")
                .bind("id", stencilKey)
                .bind("tenantKey", command.tenantKey)
                .mapTo(Boolean::class.java)
                .one()

            // Upsert stencil row (name/description/tags). Versions are handled
            // separately below.
            handle.createUpdate(
                """
                INSERT INTO stencils (id, tenant_key, catalog_key, name, description, tags, created_at, updated_at, created_by, updated_by)
                VALUES (:id, :tenantKey, :catalogKey, :name, :description, :tags::jsonb, NOW(), NOW(), :createdBy, :updatedBy)
                ON CONFLICT (tenant_key, catalog_key, id) DO UPDATE
                SET name = :name, description = :description, tags = :tags::jsonb, updated_at = NOW(), updated_by = :updatedBy
                """,
            )
                .bind("id", stencilKey)
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("name", command.name)
                .bind("description", command.description)
                .bind("tags", tagsJson)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .execute()

            // Drop any draft for this stencil — imports supersede local
            // work-in-progress regardless of which version we end up writing.
            handle.createUpdate(
                """
                DELETE FROM stencil_versions
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND stencil_key = :stencilKey AND status = 'draft'
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("stencilKey", stencilKey)
                .execute()

            // Is there already a published row for the requested (stencil, version)?
            // Compare JSONB by value, not text — postgres normalises whitespace and
            // object-key order so semantic equality is the right call. The parameter
            // schema is part of the version's identity, so a row that matches the
            // content but carries a different schema is a genuine conflict, not an
            // idempotent re-import (`IS NOT DISTINCT FROM` treats NULL = NULL).
            val existingMatchOrNull = handle.createQuery(
                """
                SELECT (content = :content::jsonb
                        AND parameter_schema IS NOT DISTINCT FROM :parameterSchema::jsonb) AS matches
                FROM stencil_versions
                WHERE tenant_key = :tenantKey
                  AND catalog_key = :catalogKey
                  AND stencil_key = :stencilKey
                  AND id = :version
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("stencilKey", stencilKey)
                .bind("version", VersionKey.of(command.version))
                .bind("content", contentJson)
                .bind("parameterSchema", parameterSchemaJson)
                .mapTo(Boolean::class.java)
                .findOne()
                .orElse(null)

            when {
                existingMatchOrNull == null -> {
                    // No row at this (slug, version) — install at the requested version.
                    insertVersion(handle, command, stencilKey, command.version, contentJson, parameterSchemaJson, auditUser)
                    ImportStencilResult(
                        status = if (stencilRowExisted) InstallStatus.UPDATED else InstallStatus.INSTALLED,
                        assignedVersion = command.version,
                        wasRenumbered = false,
                    )
                }
                existingMatchOrNull -> {
                    // Idempotent re-import — same (slug, version) with identical
                    // content. Stencil row metadata was already upserted above.
                    ImportStencilResult(
                        status = InstallStatus.SKIPPED,
                        assignedVersion = command.version,
                        wasRenumbered = false,
                    )
                }
                else -> when (command.onConflict) {
                    OnStencilConflict.FAIL -> throw StencilVersionConflictException(
                        catalogKey = command.catalogKey,
                        stencilKey = stencilKey,
                        version = command.version,
                    )
                    OnStencilConflict.RENUMBER -> {
                        val newVersion = handle.createQuery(
                            "SELECT COALESCE(MAX(id), 0) + 1 FROM stencil_versions WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND stencil_key = :stencilKey",
                        )
                            .bind("tenantKey", command.tenantKey)
                            .bind("catalogKey", command.catalogKey)
                            .bind("stencilKey", stencilKey)
                            .mapTo(Int::class.java)
                            .one()
                        insertVersion(handle, command, stencilKey, newVersion, contentJson, parameterSchemaJson, auditUser)
                        ImportStencilResult(
                            status = if (stencilRowExisted) InstallStatus.UPDATED else InstallStatus.INSTALLED,
                            assignedVersion = newVersion,
                            wasRenumbered = true,
                        )
                    }
                }
            }
        }
    }

    private fun insertVersion(
        handle: org.jdbi.v3.core.Handle,
        command: ImportStencil,
        stencilKey: StencilKey,
        version: Int,
        contentJson: String,
        parameterSchemaJson: String?,
        auditUser: java.util.UUID?,
    ) {
        handle.createUpdate(
            """
            INSERT INTO stencil_versions (id, tenant_key, catalog_key, stencil_key, content, parameter_schema, status, published_at, created_at, created_by)
            VALUES (:id, :tenantKey, :catalogKey, :stencilKey, :content::jsonb, :parameterSchema::jsonb, 'published', NOW(), NOW(), :createdBy)
            """,
        )
            .bind("id", VersionKey.of(version))
            .bind("tenantKey", command.tenantKey)
            .bind("catalogKey", command.catalogKey)
            .bind("stencilKey", stencilKey)
            .bind("content", contentJson)
            .bind("parameterSchema", parameterSchemaJson)
            .bind("createdBy", auditUser)
            .execute()
    }
}
