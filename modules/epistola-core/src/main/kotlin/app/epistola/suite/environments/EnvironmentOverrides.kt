// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versions.CatalogDraft
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What an environment uses as its latest version of a catalog, instead of the highest release it
 * serves: one of the releases it serves ([version]), or a draft of the catalog, live ([draftId]).
 * Every request through the environment that does not name an exact release gets it, ranges
 * included; a request naming an exact release still gets that release.
 *
 * @property draftLabel the draft's name as people see it ("Next version" for the next version).
 */
data class EnvironmentOverride(
    val environmentKey: EnvironmentKey,
    val catalogKey: CatalogKey,
    val version: String?,
    val draftId: UUID?,
    val draftLabel: String?,
    val setAt: OffsetDateTime,
) {
    /** What it uses, as people read it: `v1.4.2`, or the draft's name. */
    val label: String get() = version?.let { "v$it" } ?: draftLabel.orEmpty()
}

/** Refused: an override that would make an environment use something it may not. */
class EnvironmentOverrideRefusedException(message: String) : RuntimeException(message)

/**
 * Makes [environmentId] use [version] (a release it serves) or [draft] (a draft of the catalog,
 * live) as its latest version of [catalogKey], replacing any override it has. An environment marked
 * production uses only published versions, so a draft there is refused. Logged in the environment's
 * history.
 *
 * @throws EnvironmentOverrideRefusedException for a draft on a production environment, or a release
 *   the environment does not serve
 */
data class SetEnvironmentOverride(
    val environmentId: EnvironmentId,
    val catalogKey: CatalogKey,
    val version: String? = null,
    val draft: DraftRef? = null,
) : Command<EnvironmentOverride>,
    RequiresPermission {
    override val permission get() = Permission.ENVIRONMENT_DEPLOY
    override val tenantKey get() = environmentId.tenantKey

    init {
        validate("version", (version == null) != (draft == null)) { "Name a release the environment serves, or a draft" }
    }
}

@Component
class SetEnvironmentOverrideHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetEnvironmentOverride, EnvironmentOverride> {
    override fun handle(command: SetEnvironmentOverride): EnvironmentOverride = jdbi.inTransaction<EnvironmentOverride, Exception> { handle ->
        val tenantKey = command.environmentId.tenantKey
        val environmentKey = command.environmentId.key
        val production = EnvironmentOverrideStore.lockEnvironment(handle, tenantKey, environmentKey)
        val draft = command.draft?.let { ref ->
            if (production) {
                throw EnvironmentOverrideRefusedException(
                    "'${environmentKey.value}' is a production environment: it uses only published versions. Point it at a release it serves.",
                )
            }
            EnvironmentOverrideStore.draft(handle, tenantKey, command.catalogKey, ref)
                ?: throw CatalogVersionNotFoundException(command.catalogKey, ref.toString())
        }
        command.version?.let { version ->
            val served = handle.createQuery(
                """
                SELECT EXISTS (SELECT 1 FROM environment_catalog_deployments
                    WHERE tenant_key = :t AND environment_key = :e AND catalog_id = ${catalogAt("t", "c")} AND version = :v)
                """,
            )
                .bind("t", tenantKey).bind("e", environmentKey).bind("c", command.catalogKey).bind("v", version)
                .mapTo(Boolean::class.java).one()
            if (!served) {
                throw EnvironmentOverrideRefusedException(
                    "'${environmentKey.value}' does not serve ${command.catalogKey.value} v$version: deploy it there first.",
                )
            }
        }
        handle.createUpdate(
            """
            INSERT INTO environment_catalog_overrides (tenant_key, environment_key, catalog_id, version, draft_id, set_at, set_by)
            VALUES (:t, :e, ${catalogAt("t", "c")}, :v, :d, NOW(), :by)
            ON CONFLICT (tenant_key, environment_key, catalog_id)
            DO UPDATE SET version = EXCLUDED.version, draft_id = EXCLUDED.draft_id, set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by
            """,
        )
            .bind("t", tenantKey).bind("e", environmentKey).bind("c", command.catalogKey)
            .bind("v", command.version).bind("d", draft?.first).bind("by", currentUserIdOrNull()?.value)
            .execute()
        DeploymentLog.record(handle, tenantKey, environmentKey, command.catalogKey, DeploymentAction.OVERRIDE_SET, command.version, draftLabel = draft?.second)
        EnvironmentOverrideStore.list(handle, tenantKey, environmentKey, command.catalogKey).single()
    }
}

/**
 * Ends [environmentId]'s override for [catalogKey]: it uses the highest release it serves again.
 * Logged in the environment's history.
 *
 * @return whether it had one.
 */
data class ClearEnvironmentOverride(
    val environmentId: EnvironmentId,
    val catalogKey: CatalogKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.ENVIRONMENT_DEPLOY
    override val tenantKey get() = environmentId.tenantKey
}

@Component
class ClearEnvironmentOverrideHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ClearEnvironmentOverride, Boolean> {
    override fun handle(command: ClearEnvironmentOverride): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        val tenantKey = command.environmentId.tenantKey
        val environmentKey = command.environmentId.key
        EnvironmentOverrideStore.lockEnvironment(handle, tenantKey, environmentKey)
        val existing = EnvironmentOverrideStore.list(handle, tenantKey, environmentKey, command.catalogKey).singleOrNull()
            ?: return@inTransaction false
        handle.createUpdate(
            "DELETE FROM environment_catalog_overrides WHERE tenant_key = :t AND environment_key = :e AND catalog_id = ${catalogAt("t", "c")}",
        )
            .bind("t", tenantKey).bind("e", environmentKey).bind("c", command.catalogKey)
            .execute()
        DeploymentLog.record(handle, tenantKey, environmentKey, command.catalogKey, DeploymentAction.OVERRIDE_CLEARED, existing.version, draftLabel = existing.draftLabel)
        true
    }
}

/** The overrides of [environmentKey], or of every environment, for every catalog or [catalogKey] only. */
data class ListEnvironmentOverrides(
    override val tenantKey: TenantKey,
    val environmentKey: EnvironmentKey? = null,
    val catalogKey: CatalogKey? = null,
) : Query<List<EnvironmentOverride>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_VIEW
}

@Component
class ListEnvironmentOverridesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListEnvironmentOverrides, List<EnvironmentOverride>> {
    override fun handle(query: ListEnvironmentOverrides): List<EnvironmentOverride> = jdbi.withHandle<List<EnvironmentOverride>, Exception> { handle ->
        EnvironmentOverrideStore.list(handle, query.tenantKey, query.environmentKey, query.catalogKey)
    }
}

/** The SQL the override commands, queries and generation share. */
object EnvironmentOverrideStore {

    /** Locks the environment row, as deploys do, and says whether it is production. */
    internal fun lockEnvironment(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey): Boolean = handle.createQuery(
        "SELECT production FROM environments WHERE tenant_key = :t AND id = :e FOR UPDATE",
    )
        .bind("t", tenantKey).bind("e", environmentKey)
        .mapTo(Boolean::class.java)
        .findOne()
        .orElseThrow { EnvironmentNotFoundException(tenantKey, environmentKey) }

    /** The id and label of the draft of [catalogKey] that [ref] names, or null when there is none. */
    internal fun draft(handle: Handle, tenantKey: TenantKey, catalogKey: CatalogKey, ref: DraftRef): Pair<UUID, String>? {
        val match = when (ref) {
            DraftRef.Next -> "v.is_next"
            is DraftRef.Id -> "v.version_id = :id"
        }
        return handle.createQuery(
            """
            SELECT v.version_id, $LABEL AS label FROM catalog_versions v
            WHERE v.tenant_key = :t AND v.catalog_id = ${catalogAt("t", "c")} AND v.kind = 'draft' AND $match
            """,
        )
            .bind("t", tenantKey).bind("c", catalogKey)
            .also { q -> (ref as? DraftRef.Id)?.let { q.bind("id", it.id) } }
            .map { rs, _ -> rs.getObject("version_id", UUID::class.java) to rs.getString("label") }
            .findOne()
            .orElse(null)
    }

    fun list(handle: Handle, tenantKey: TenantKey, environmentKey: EnvironmentKey?, catalogKey: CatalogKey?): List<EnvironmentOverride> = handle.createQuery(
        """
        SELECT o.environment_key, c.catalog_key, o.version, o.draft_id, o.set_at,
               CASE WHEN v.version_id IS NULL THEN NULL ELSE $LABEL END AS draft_label
        FROM environment_catalog_overrides o
        JOIN catalogs c ON c.tenant_key = o.tenant_key AND c.catalog_id = o.catalog_id
        LEFT JOIN catalog_versions v ON v.tenant_key = o.tenant_key AND v.version_id = o.draft_id
        WHERE o.tenant_key = :t
          ${if (environmentKey != null) "AND o.environment_key = :e" else ""}
          ${if (catalogKey != null) "AND c.catalog_key = :c" else ""}
        ORDER BY o.environment_key, c.catalog_key
        """,
    )
        .bind("t", tenantKey)
        .also { q -> environmentKey?.let { q.bind("e", it) } }
        .also { q -> catalogKey?.let { q.bind("c", it) } }
        .map { rs, _ ->
            EnvironmentOverride(
                environmentKey = EnvironmentKey.of(rs.getString("environment_key")),
                catalogKey = CatalogKey.of(rs.getString("catalog_key")),
                version = rs.getString("version"),
                draftId = rs.getObject("draft_id", UUID::class.java),
                draftLabel = rs.getString("draft_label"),
                setAt = rs.getObject("set_at", OffsetDateTime::class.java),
            )
        }
        .list()

    /** A draft's label in SQL, over alias `v`: its name, or "Next version" for the next version. */
    private val LABEL = "CASE WHEN v.is_next THEN '${CatalogDraft.NEXT_LABEL}' ELSE v.name END"
}
