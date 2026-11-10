// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogCanonicalizer
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionRegistry
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportConnections
import app.epistola.suite.support.connection.SupportPause
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.sharing.GetSharingRestrictions
import app.epistola.suite.support.sharing.SharingRestriction
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.util.UUID

/** Whether this installation can keep a mirror with a connection at all, and if not, why not. */
enum class CatalogMirrorAvailability {
    /** The support connection is off (`epistola.support.enabled=false`), or there is no such connection. */
    SUPPORT_OFF,

    /** The installation turned the mirror off (`epistola.support.mirror.enabled=false`). */
    MIRROR_OFF,

    /** Tenants are confidential to Epistola (ADR 0052 §1): a release would name its client, so nothing is mirrored. */
    CONFIDENTIAL,

    /** An installation administrator does not allow the catalog mirror for any tenant (ADR 0052 §5). */
    NOT_ALLOWED,

    /** The support connection is on but not registered, or Epistola refused it. */
    NOT_CONNECTED,

    /** Registered, waiting for Epistola to activate the installation for a customer. */
    PENDING,

    /** Registered, but an installation administrator has not selected the catalog mirror. */
    NOT_SELECTED,

    /** Connected, but Epistola has not granted the catalog mirror to this installation. */
    NOT_GRANTED,

    /** Granted, but Epistola paused it for now: the next round after the pause sends what changed. */
    PAUSED,

    /** The mirror can be used. */
    AVAILABLE,
}

/** A tenant's consent record. */
data class CatalogMirrorConsent(
    val shareByDefault: Boolean,
    /** The statement agreed to; null for a tenant that opted out without ever opting in. */
    val consentVersion: String?,
    /** When it last opted in; null for a tenant that opted out without ever opting in. */
    val optedInAt: OffsetDateTime?,
    val optedInBy: String?,
    val optedOutAt: OffsetDateTime?,
    val withdrawalConfirmedAt: OffsetDateTime?,
    /**
     * The tenant made no choice of its own and follows the installation default
     * ([CatalogMirrorDefault]); then [optedInBy] names the administrator who set it.
     */
    val followsDefault: Boolean = false,
) {
    val sharing: Boolean get() = optedOutAt == null

    /** Opted out, and the support service has not confirmed yet that it holds nothing of the tenant. */
    val withdrawalPending: Boolean get() = optedOutAt != null && withdrawalConfirmedAt == null
}

/** One release the last round could not upload, and why. */
data class CatalogMirrorFailedRelease(
    val contentHash: String,
    val error: String,
    /** Rounds in a row it failed; its back-off doubles with each. */
    val attempts: Int = 1,
    /** Not uploaded again before this. */
    val nextAttemptAt: java.time.OffsetDateTime? = null,
)

/** How the last reconcile round for a tenant went. */
data class CatalogMirrorRound(
    val lastAttemptAt: OffsetDateTime?,
    val lastSuccessAt: OffsetDateTime?,
    val bindingsSent: Int,
    val releasesUploaded: Int,
    val releasesMissing: Int,
    val failedReleases: List<CatalogMirrorFailedRelease>,
    val consecutiveFailures: Int,
    val nextAttemptAt: OffsetDateTime?,
    val lastError: String?,
    val syncRequestedAt: OffsetDateTime?,
)

/** One catalog of the tenant, and whether its releases are shared. */
data class CatalogMirrorEntry(
    val catalog: CatalogId,
    val registry: String,
    val name: String,
    val policy: CatalogMirrorPolicy,
    val sharing: CatalogMirrorSharing,
    val releases: Int,
    /** When the mirror last took the tenant's set with this catalog in it; null when it has not yet. */
    val lastSyncedAt: OffsetDateTime?,
    /** Its releases that would be shared but are not sent, because a release they depend on is never shared. */
    val withheldReleases: Int = 0,
    /** The never-shared catalogs (`namespace/key`) that withhold them, sorted. */
    val withheldBecause: List<String> = emptyList(),
    /** Its releases sent although it is not shared itself, because a shared release depends on them. */
    val sentAsDependency: Int = 0,
    /** Epistola supplies it (the system catalog, a bundled one): it is never withheld. */
    val suppliedByEpistola: Boolean = false,
) {
    val address: String get() = "${catalog.namespace}/${catalog.key.value}"

    /** The catalog's key as text (templates see a value class as its underlying value, not the class). */
    val key: String get() = catalog.key.value
}

/** A connection the tenant can share its catalogs with: one that the installation selected the mirror for. */
data class CatalogMirrorConnection(val id: UUID, val name: String)

/**
 * The tenant's catalog-mirror page for one connection ([connectionId], named [connectionName]):
 * whether the mirror can be used with it, the tenant's consent, the last round, and one page of its
 * catalogs with what is shared. [connections] are every connection the mirror is selected for, to
 * switch between.
 *
 * @property canChangeSharing the viewer may opt in or out, share and inherit ([Permission.INTEGRATIONS_MANAGE]).
 * @property canWithhold the viewer may mark a catalog never to be shared ([Permission.CATALOG_MANAGE]).
 */
data class CatalogMirrorSettings(
    val tenantKey: TenantKey,
    val availability: CatalogMirrorAvailability,
    val consent: CatalogMirrorConsent?,
    val round: CatalogMirrorRound?,
    val catalogs: List<CatalogMirrorEntry>,
    val totalCatalogs: Int,
    val offset: Int,
    val limit: Int,
    val canChangeSharing: Boolean,
    val canWithhold: Boolean,
    /** The installation's default, which applies while the tenant has no choice of its own. */
    val installationDefault: CatalogMirrorDefault = CatalogMirrorDefault.NONE,
    /** The pause in force while [availability] is [CatalogMirrorAvailability.PAUSED]; null otherwise. */
    val pause: SupportPause? = null,
    val connectionId: UUID,
    val connectionName: String = "",
    val connections: List<CatalogMirrorConnection> = emptyList(),
) {
    /** The tenant follows the installation default: it has no consent record, or one the default wrote. */
    val followsDefault: Boolean get() = consent?.followsDefault ?: true

    val consentVersion: String get() = CatalogMirror.CONSENT_VERSION
    val sharing: Boolean get() = consent?.sharing == true

    /** The support connection is on and the installation keeps a mirror, so a choice made here takes effect once Epistola grants it. */
    private val supportOn: Boolean
        get() = availability != CatalogMirrorAvailability.SUPPORT_OFF && availability != CatalogMirrorAvailability.MIRROR_OFF && availability != CatalogMirrorAvailability.NOT_ALLOWED &&
            availability != CatalogMirrorAvailability.CONFIDENTIAL

    /** Opting in is offered while the tenant does not share, the viewer may decide, and the support connection is on. */
    val canOptIn: Boolean get() = canChangeSharing && !sharing && supportOn

    /** Opting out is offered whenever the tenant shares: stopping is always possible. */
    val canOptOut: Boolean get() = canChangeSharing && sharing

    /** "Sync now" has somewhere to land only while the mirror can be used and the tenant has something to send or withdraw. */
    val canSyncNow: Boolean
        get() = canChangeSharing && availability == CatalogMirrorAvailability.AVAILABLE && (sharing || consent?.withdrawalPending == true)

    /** Why opting in is not offered to a tenant that does not share; null when it is offered, or the tenant shares. */
    val optInWithheldReason: String?
        get() = when {
            sharing || canOptIn -> null
            !canChangeSharing -> "Only a tenant administrator who manages integrations can turn sharing on."
            availability == CatalogMirrorAvailability.SUPPORT_OFF -> "Sharing can be turned on once an installation administrator turns the support connection on."
            availability == CatalogMirrorAvailability.CONFIDENTIAL -> "This installation keeps its tenants confidential to Epistola, so catalogs are not shared; an installation administrator decides this."
            availability == CatalogMirrorAvailability.NOT_ALLOWED -> "This installation does not allow sharing catalogs with Epistola; an installation administrator decides this."
            else -> "Sharing can be turned on once an installation administrator turns the catalog mirror on."
        }

    /** Why "Sync now" is not offered; null when it is. */
    val syncWithheldReason: String?
        get() = when {
            canSyncNow -> null
            !canChangeSharing -> "Only a tenant administrator who manages integrations can sync."
            availability == CatalogMirrorAvailability.SUPPORT_OFF -> "Nothing syncs while the support connection is off."
            availability == CatalogMirrorAvailability.MIRROR_OFF -> "Nothing syncs while this installation does not keep a catalog mirror."
            availability == CatalogMirrorAvailability.CONFIDENTIAL -> "Nothing syncs: this installation keeps its tenants confidential to Epistola."
            availability == CatalogMirrorAvailability.NOT_ALLOWED -> "Nothing syncs: this installation does not allow sharing catalogs with Epistola."
            availability == CatalogMirrorAvailability.NOT_CONNECTED -> "Syncing is possible once this installation is connected to Epistola support."
            availability == CatalogMirrorAvailability.PENDING -> "Syncing is possible once Epistola has activated this installation."
            availability == CatalogMirrorAvailability.NOT_SELECTED -> "Syncing is possible once an installation administrator selects the catalog mirror under Platform → Support connections."
            availability == CatalogMirrorAvailability.NOT_GRANTED -> "Syncing is possible once Epistola has granted the catalog mirror to this installation."
            availability == CatalogMirrorAvailability.PAUSED -> "Epistola paused the catalog mirror; syncing resumes once the pause ends."
            else -> "Nothing to sync: this tenant does not share catalogs, and Epistola holds nothing of it."
        }

    /** Policies the viewer may pick. */
    val policyChoices: List<CatalogMirrorPolicy>
        get() = CatalogMirrorPolicy.entries.filter { if (it == CatalogMirrorPolicy.NEVER) canWithhold else canChangeSharing }

    /**
     * The policies the viewer can pick between for [entry]: its own, and the ones they may set. A
     * catalog Epistola supplies offers no [CatalogMirrorPolicy.NEVER] ([withholdingRefusedReason]).
     */
    fun policyOptions(entry: CatalogMirrorEntry): List<CatalogMirrorPolicy> = CatalogMirrorPolicy.entries.filter {
        it == entry.policy || (it in policyChoices && !(it == CatalogMirrorPolicy.NEVER && entry.suppliedByEpistola))
    }

    /** Why [entry] offers a viewer who may withhold no "Never share": Epistola supplies it. Null otherwise. */
    fun withholdingRefusedReason(entry: CatalogMirrorEntry): String? = CatalogMirrorPolicy.SUPPLIED_NOT_WITHHELD_REASON.takeIf { canWithhold && entry.suppliedByEpistola && entry.policy != CatalogMirrorPolicy.NEVER }

    /** Whether the viewer can change [entry]'s policy to anything else. */
    fun canSetPolicy(entry: CatalogMirrorEntry): Boolean = policyOptions(entry).any { it != entry.policy }

    val previousOffset: Int get() = (offset - limit).coerceAtLeast(0)
    val nextOffset: Int get() = offset + limit
    val hasPrevious: Boolean get() = offset > 0
    val hasNext: Boolean get() = offset + catalogs.size < totalCatalogs
}

/**
 * Reads the catalog-mirror page of a tenant. No network I/O: the connection state is the cached
 * status. Catalogs are ordered and paged in the database.
 */
data class GetCatalogMirrorSettings(
    override val tenantKey: TenantKey,
    val offset: Int = 0,
    val limit: Int = DEFAULT_LIMIT,
    val connectionId: UUID,
) : Query<CatalogMirrorSettings>,
    RequiresPermission {
    override val permission = Permission.INTEGRATIONS_MANAGE

    /** [offset] and [limit] as the handler applies them: a negative offset is 0, the limit 1..[MAX_LIMIT]. */
    val pageOffset: Int get() = offset.coerceAtLeast(0)
    val pageLimit: Int get() = limit.coerceIn(1, MAX_LIMIT)

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}

@Component
class GetCatalogMirrorSettingsHandler(
    private val jdbi: Jdbi,
    private val registry: SupportConnectionRegistry,
    private val connections: SupportConnections,
    private val properties: CatalogMirrorProperties,
    private val privacy: SupportPrivacy,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetCatalogMirrorSettings, CatalogMirrorSettings> {
    override fun handle(query: GetCatalogMirrorSettings): CatalogMirrorSettings = jdbi.withHandle<CatalogMirrorSettings, Exception> { handle ->
        val principal = SecurityContext.current()
        val connectionId = query.connectionId
        val connection = registry.find(connectionId)
        val consent = consent(handle, connectionId, query.tenantKey)
        val round = CatalogMirrorStateStore.round(handle, connectionId, query.tenantKey, objectMapper)
        var total = 0
        val catalogs = handle.createQuery(
            """
            SELECT c.catalog_id, c.namespace, c.catalog_key, c.registry, c.name,
                   COALESCE(starts_with(c.source_url, :classpath), FALSE) AS supplied,
                   p.policy,
                   COALESCE(s.synced_catalogs @> jsonb_build_array(CAST(c.catalog_id AS TEXT)), FALSE) AS synced,
                   (SELECT count(*) FROM catalog_versions v
                    WHERE v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id AND v.state = 'released') AS releases,
                   count(*) OVER () AS total
            FROM catalogs c
            LEFT JOIN support_mirror_catalog_policies p ON p.connection_id = :connection AND p.tenant_key = c.tenant_key AND p.catalog_id = c.catalog_id
            LEFT JOIN support_mirror_state s ON s.connection_id = :connection AND s.tenant_key = c.tenant_key
            WHERE c.tenant_key = :t
            ORDER BY c.namespace, c.catalog_key
            LIMIT :limit OFFSET :offset
            """,
        )
            .bind("connection", connectionId)
            .bind("t", query.tenantKey.value)
            .bind("limit", query.pageLimit)
            .bind("offset", query.pageOffset)
            .bind("classpath", Catalog.CLASSPATH_SOURCE)
            .map { rs, _ ->
                total = rs.getInt("total")
                rs.getObject("catalog_id", UUID::class.java) to entry(rs, query.tenantKey, consent, round)
            }
            .list()
            .let { rows ->
                val dependencies = dependencyEffects(handle, connectionId, query.tenantKey, rows.map { it.first })
                rows.map { (id, entry) ->
                    dependencies[id]?.let { entry.copy(withheldReleases = it.withheld, withheldBecause = it.because, sentAsDependency = it.asDependency) } ?: entry
                }
            }
        if (catalogs.isEmpty() && query.pageOffset > 0) {
            total = handle.createQuery("SELECT count(*) FROM catalogs WHERE tenant_key = :t").bind("t", query.tenantKey.value).mapTo(Int::class.java).one()
        }
        CatalogMirrorSettings(
            tenantKey = query.tenantKey,
            connectionId = connectionId,
            connectionName = connections.find(connectionId)?.name ?: "this support connection",
            connections = registry.all().filter { it.isSelected(SupportScope.MIRROR) || it.id == connectionId }
                .mapNotNull { c -> connections.find(c.id)?.let { CatalogMirrorConnection(c.id, it.name) } },
            availability = availability(connection),
            pause = connection?.let { mirrorPause(it) },
            consent = consent,
            round = round,
            catalogs = catalogs,
            totalCatalogs = total,
            offset = query.pageOffset,
            limit = query.pageLimit,
            canChangeSharing = principal.hasPermission(query.tenantKey, Permission.INTEGRATIONS_MANAGE),
            canWithhold = principal.hasPermission(query.tenantKey, Permission.CATALOG_MANAGE),
            installationDefault = readMirrorDefault(handle, connectionId),
        )
    }

    private class DependencyEffect(val withheld: Int, val because: List<String>, val asDependency: Int)

    /**
     * For the catalogs [catalogIds] on the page: how many of their releases a never-shared
     * dependency withholds and which catalogs those are, and how many are sent only because a shared
     * release depends on them ([CatalogMirror.SENT_RELEASES_SQL]).
     */
    private fun dependencyEffects(handle: Handle, connectionId: UUID, tenantKey: TenantKey, catalogIds: List<UUID>): Map<UUID, DependencyEffect> {
        if (catalogIds.isEmpty()) return emptyMap()
        return handle.createQuery(
            """
            ${CatalogMirror.SENT_RELEASES_SQL},
            withheld AS (
                SELECT rv.catalog_id, count(DISTINCT b.root_id) AS releases,
                       array_agg(DISTINCT bc.namespace || '/' || bc.catalog_key ORDER BY bc.namespace || '/' || bc.catalog_key) AS blockers
                FROM blocked b
                JOIN catalog_versions rv ON rv.tenant_key = :t AND rv.version_id = b.root_id
                JOIN catalogs bc ON bc.tenant_key = :t AND bc.catalog_id = b.blocker_id
                GROUP BY rv.catalog_id
            ),
            dependency AS (
                SELECT v.catalog_id, count(*) AS releases
                FROM sent s
                JOIN catalog_versions v ON v.tenant_key = :t AND v.version_id = s.version_id
                WHERE NOT EXISTS (SELECT 1 FROM chosen ch WHERE ch.version_id = s.version_id)
                GROUP BY v.catalog_id
            )
            SELECT c.catalog_id, COALESCE(w.releases, 0) AS withheld, w.blockers, COALESCE(d.releases, 0) AS as_dependency
            FROM catalogs c
            LEFT JOIN withheld w ON w.catalog_id = c.catalog_id
            LEFT JOIN dependency d ON d.catalog_id = c.catalog_id
            WHERE c.tenant_key = :t AND c.catalog_id IN (<catalogs>) AND (w.catalog_id IS NOT NULL OR d.catalog_id IS NOT NULL)
            """,
        )
            .bind("connection", connectionId)
            .bind("t", tenantKey.value)
            .bindList("catalogs", catalogIds)
            .map { rs, _ ->
                rs.getObject("catalog_id", UUID::class.java) to DependencyEffect(
                    withheld = rs.getInt("withheld"),
                    because = (rs.getArray("blockers")?.array as? Array<*>)?.map { it.toString() }.orEmpty(),
                    asDependency = rs.getInt("as_dependency"),
                )
            }
            .list()
            .toMap()
    }

    /** The pause in force that holds the mirror back, or null. */
    private fun mirrorPause(connection: SupportConnection): SupportPause? = (connection.status().state as? SupportConnectionState.Active)?.let { state ->
        state.pause?.takeIf { SupportScope.MIRROR in state.scopes && SupportScope.MIRROR in it.scopes && it.inForceAt(EpistolaClock.instant()) }
    }

    private fun availability(connection: SupportConnection?): CatalogMirrorAvailability {
        if (connection == null || connection.status().state == SupportConnectionState.Disabled) return CatalogMirrorAvailability.SUPPORT_OFF
        if (!properties.enabled) return CatalogMirrorAvailability.MIRROR_OFF
        if (privacy.forConnection(connection.id).confidential) return CatalogMirrorAvailability.CONFIDENTIAL
        if (GetSharingRestrictions(connection.id).query().of(CatalogMirror.SECTION) == SharingRestriction.NOT_ALLOWED) return CatalogMirrorAvailability.NOT_ALLOWED
        return when (connection.status().state) {
            SupportConnectionState.Disabled -> CatalogMirrorAvailability.SUPPORT_OFF

            SupportConnectionState.Unregistered, is SupportConnectionState.Expired, is SupportConnectionState.Refused, is SupportConnectionState.Disconnected, is SupportConnectionState.Declined -> CatalogMirrorAvailability.NOT_CONNECTED

            is SupportConnectionState.Pending ->
                if (connection.isSelected(SupportScope.MIRROR)) CatalogMirrorAvailability.PENDING else CatalogMirrorAvailability.NOT_SELECTED

            is SupportConnectionState.Active -> when {
                !connection.isSelected(SupportScope.MIRROR) -> CatalogMirrorAvailability.NOT_SELECTED
                connection.hasScope(SupportScope.MIRROR) -> CatalogMirrorAvailability.AVAILABLE
                mirrorPause(connection) != null -> CatalogMirrorAvailability.PAUSED
                else -> CatalogMirrorAvailability.NOT_GRANTED
            }
        }
    }

    private fun consent(handle: Handle, connectionId: UUID, tenantKey: TenantKey): CatalogMirrorConsent? = handle.createQuery(
        """
        SELECT COALESCE(${shareByDefault("m")}, FALSE) AS share_by_default, m.statement_version AS consent_version, m.given_at AS opted_in_at,
               u.display_name, m.withdrawn_at AS opted_out_at, m.withdrawal_confirmed_at, m.follows_default
        FROM support_sharing_choice m LEFT JOIN users u ON u.id = m.given_by
        WHERE ${mirrorSharing("m")} AND m.tenant_key = :t
        """,
    )
        .bind("connection", connectionId)
        .bind("t", tenantKey.value)
        .map { rs, _ ->
            CatalogMirrorConsent(
                shareByDefault = rs.getBoolean("share_by_default"),
                consentVersion = rs.getString("consent_version"),
                optedInAt = rs.getObject("opted_in_at", OffsetDateTime::class.java),
                optedInBy = rs.getString("display_name"),
                optedOutAt = rs.getObject("opted_out_at", OffsetDateTime::class.java),
                withdrawalConfirmedAt = rs.getObject("withdrawal_confirmed_at", OffsetDateTime::class.java),
                followsDefault = rs.getBoolean("follows_default"),
            )
        }
        .findOne()
        .orElse(null)

    private fun entry(rs: ResultSet, tenantKey: TenantKey, consent: CatalogMirrorConsent?, round: CatalogMirrorRound?): CatalogMirrorEntry {
        val policy = rs.getString("policy")?.let(CatalogMirrorPolicy::valueOf) ?: CatalogMirrorPolicy.INHERIT
        val sharing = CatalogMirrorSharing.resolve(consent?.takeIf { it.sharing }?.shareByDefault, policy)
        // Synced: its releases were in the set the mirror last accepted, and it is shared still.
        val lastSyncedAt = round?.lastSuccessAt?.takeIf { sharing == CatalogMirrorSharing.SHARED && rs.getBoolean("synced") }
        return CatalogMirrorEntry(
            catalog = CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), TenantId(tenantKey)),
            registry = rs.getString("registry"),
            name = rs.getString("name"),
            policy = policy,
            sharing = sharing,
            releases = rs.getInt("releases"),
            lastSyncedAt = lastSyncedAt,
            suppliedByEpistola = rs.getBoolean("supplied"),
        )
    }
}

/** A deployment of a shared release: the environment, and whether it is a production one. */
data class MirrorDeployment(val environment: String, val production: Boolean)

/** A release a shared release pins. The pinned release is sent too, as a binding of its own. */
data class MirrorDependency(val registry: String, val namespace: String, val key: String, val version: String?, val contentHash: String?)

/** One release binding the tenant shares: `registry/namespace/key @ version -> contentHash`. */
data class MirrorBinding(
    val catalogRowId: UUID,
    val versionRowId: UUID,
    val catalog: CatalogId,
    val registry: String,
    val version: String,
    val contentHash: String,
    val releasedAt: OffsetDateTime,
    val deployments: List<MirrorDeployment>,
    val dependencies: List<MirrorDependency>,
)

/** Where the next page of [ListMirrorBindings] starts. */
data class MirrorBindingCursor(val catalogRowId: UUID, val versionRowId: UUID)

/** One page of bindings, and where the next one starts (null after the last). */
data class MirrorBindingsPage(val bindings: List<MirrorBinding>, val next: MirrorBindingCursor?)

/**
 * The release bindings a tenant sends now, keyset-paged in a stable order
 * ([CatalogMirror.SENT_RELEASES_SQL]): every released version of every catalog the sharing rule
 * ([CatalogMirrorSharing.resolve]) shares, and every release those depend on, all the way down,
 * except a release that depends on a catalog marked never to be shared. Each with the environments
 * it is deployed to and the releases it pins. Never a draft or a draft deployment; nothing while the
 * tenant does not share. For the reconcile.
 */
data class ListMirrorBindings(
    val tenantKey: TenantKey,
    val after: MirrorBindingCursor? = null,
    val limit: Int = DEFAULT_LIMIT,
    /** The connection the bindings go to: each has its own sharing choice and catalog policies. */
    val connectionId: UUID,
) : Query<MirrorBindingsPage>,
    SystemInternal {
    /** [limit] as the handler applies it: 1..[MAX_LIMIT]. */
    val pageLimit: Int get() = limit.coerceIn(1, MAX_LIMIT)

    companion object {
        const val DEFAULT_LIMIT = 1_000
        const val MAX_LIMIT = 5_000
    }
}

@Component
class ListMirrorBindingsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListMirrorBindings, MirrorBindingsPage> {
    override fun handle(query: ListMirrorBindings): MirrorBindingsPage = jdbi.withHandle<MirrorBindingsPage, Exception> { handle ->
        val rows = handle.createQuery(
            """
            ${CatalogMirror.SENT_RELEASES_SQL}
            SELECT c.catalog_id, c.registry, c.namespace, c.catalog_key,
                   v.version_id, v.version, v.content_hash, v.released_at
            FROM sent s
            JOIN catalog_versions v ON v.tenant_key = :t AND v.version_id = s.version_id
            JOIN catalogs c ON c.tenant_key = v.tenant_key AND c.catalog_id = v.catalog_id
            WHERE (CAST(:afterCatalog AS UUID) IS NULL OR (c.catalog_id, v.version_id) > (CAST(:afterCatalog AS UUID), CAST(:afterVersion AS UUID)))
            ORDER BY c.catalog_id, v.version_id
            LIMIT :limit
            """,
        )
            .bind("connection", query.connectionId)
            .bind("t", query.tenantKey.value)
            .bind("afterCatalog", query.after?.catalogRowId)
            .bind("afterVersion", query.after?.versionRowId)
            .bind("limit", query.pageLimit + 1)
            .map { rs, _ -> BindingRow(rs) }
            .list()
        val page = rows.take(query.pageLimit)
        val versionIds = page.map { it.versionId }
        val deployments = deployments(handle, query.tenantKey, versionIds)
        val dependencies = dependencies(handle, query.tenantKey, versionIds)
        MirrorBindingsPage(
            bindings = page.map { row ->
                MirrorBinding(
                    catalogRowId = row.catalogId,
                    versionRowId = row.versionId,
                    catalog = CatalogId(row.namespace, CatalogKey.of(row.key), TenantId(query.tenantKey)),
                    registry = row.registry,
                    version = row.version,
                    contentHash = row.contentHash,
                    releasedAt = row.releasedAt,
                    deployments = deployments[row.versionId].orEmpty(),
                    dependencies = dependencies[row.versionId].orEmpty(),
                )
            },
            next = if (rows.size > query.pageLimit) page.last().let { MirrorBindingCursor(it.catalogId, it.versionId) } else null,
        )
    }

    private class BindingRow(rs: ResultSet) {
        val catalogId: UUID = rs.getObject("catalog_id", UUID::class.java)
        val registry: String = rs.getString("registry")
        val namespace: String = rs.getString("namespace")
        val key: String = rs.getString("catalog_key")
        val versionId: UUID = rs.getObject("version_id", UUID::class.java)
        val version: String = rs.getString("version")
        val contentHash: String = rs.getString("content_hash")
        val releasedAt: OffsetDateTime = rs.getObject("released_at", OffsetDateTime::class.java)
    }

    private fun deployments(handle: Handle, tenantKey: TenantKey, versionIds: List<UUID>): Map<UUID, List<MirrorDeployment>> {
        if (versionIds.isEmpty()) return emptyMap()
        return handle.createQuery(
            """
            SELECT d.target_version_id, d.environment_key, e.production
            FROM environment_catalog_deployments d
            JOIN environments e ON e.tenant_key = d.tenant_key AND e.id = d.environment_key
            WHERE d.tenant_key = :t AND d.target_version_id IN (<versions>)
            ORDER BY d.target_version_id, d.environment_key
            """,
        )
            .bind("t", tenantKey.value)
            .bindList("versions", versionIds)
            .map { rs, _ -> rs.getObject("target_version_id", UUID::class.java) to MirrorDeployment(rs.getString("environment_key"), rs.getBoolean("production")) }
            .list()
            .groupBy({ it.first }, { it.second })
    }

    /**
     * A release's pins: by its own content hash when the pin is one, else by the hash of the
     * release it pins when that is here (an older fingerprint pin).
     */
    private fun dependencies(handle: Handle, tenantKey: TenantKey, versionIds: List<UUID>): Map<UUID, List<MirrorDependency>> {
        if (versionIds.isEmpty()) return emptyMap()
        return handle.createQuery(
            """
            SELECT p.version_id, p.registry, p.namespace, p.dependency_key, p.release_version, p.release_fingerprint,
                   t.content_hash AS target_content_hash
            FROM catalog_version_dependencies p
            LEFT JOIN catalog_versions t ON t.tenant_key = p.tenant_key AND t.version_id = p.target_version_id AND t.state = 'released'
            WHERE p.tenant_key = :t AND p.version_id IN (<versions>)
            ORDER BY p.version_id, p.position
            """,
        )
            .bind("t", tenantKey.value)
            .bindList("versions", versionIds)
            .map { rs, _ ->
                val pin = rs.getString("release_fingerprint")
                rs.getObject("version_id", UUID::class.java) to MirrorDependency(
                    registry = rs.getString("registry"),
                    namespace = rs.getString("namespace"),
                    key = rs.getString("dependency_key"),
                    version = rs.getString("release_version"),
                    contentHash = pin?.takeIf(CatalogCanonicalizer::isContentHash) ?: rs.getString("target_content_hash"),
                )
            }
            .list()
            .groupBy({ it.first }, { it.second })
    }
}

/** A tenant with a consent record: whether it shares now, or is waiting for its withdrawal to be confirmed. */
data class MirrorTenant(val tenantKey: TenantKey, val sharing: Boolean, val withdrawalPending: Boolean)

/** Every tenant with a consent record. */
data class MirrorTenants(val tenants: List<MirrorTenant>) {
    val sharing: List<TenantKey> get() = tenants.filter { it.sharing }.map { it.tenantKey }
    val withdrawalPending: List<TenantKey> get() = tenants.filter { it.withdrawalPending }.map { it.tenantKey }
}

/** The tenants the reconcile with [connectionId] has to look at: those sharing, and those whose withdrawal is unconfirmed. For the reconcile. */
data class ListMirrorTenants(val connectionId: UUID) :
    Query<MirrorTenants>,
    SystemInternal

@Component
class ListMirrorTenantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListMirrorTenants, MirrorTenants> {
    override fun handle(query: ListMirrorTenants): MirrorTenants = jdbi.withHandle<MirrorTenants, Exception> { handle ->
        MirrorTenants(
            handle.createQuery(
                """
                SELECT tenant_key, withdrawn_at IS NULL AS sharing, (withdrawn_at IS NOT NULL AND withdrawal_confirmed_at IS NULL) AS withdrawal_pending
                FROM support_sharing_choice
                WHERE ${mirrorSharing()} AND (withdrawn_at IS NULL OR withdrawal_confirmed_at IS NULL)
                ORDER BY tenant_key
                """,
            )
                .bind("connection", query.connectionId)
                .map { rs, _ -> MirrorTenant(TenantKey.of(rs.getString("tenant_key")), rs.getBoolean("sharing"), rs.getBoolean("withdrawal_pending")) }
                .list(),
        )
    }
}
