// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.support.connection.SupportConnections
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.UUID

/**
 * The catalog mirror (`docs/catalog-mirror.md`, ADR 0051): a custody copy, kept by the Epistola
 * support service, of the releases a tenant chose to share, so Epistola can check them against new
 * suite versions. It is not a registry: nothing resolves names against it, and what stops being
 * shared is deleted there.
 *
 * @property enabled installation gate. The mirror also needs the support connection on
 *   (`epistola.support.enabled`) and the `mirror` scope granted by Epistola; each tenant opts in
 *   on its own. False: nothing is sent, whatever tenants chose; tenants that opted out or were
 *   deleted are still withdrawn, so what they asked to have deleted is.
 * @property interval how long one reconcile round waits after the previous one ended.
 * @property roundBudget the most one round spends uploading; what is left waits for the next round,
 *   so a slow support service never holds a round for long.
 */
@ConfigurationProperties(prefix = "epistola.support.mirror")
data class CatalogMirrorProperties(
    val enabled: Boolean = true,
    val interval: Duration = Duration.ofMinutes(15),
    val roundBudget: Duration = Duration.ofMinutes(5),
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "epistola.support.mirror.interval must be positive" }
    }
}

@Configuration
@EnableConfigurationProperties(CatalogMirrorProperties::class)
class CatalogMirrorConfiguration

/**
 * A catalog's own mirror policy. [INHERIT] is the absence of one: the tenant's choice at opt-in
 * decides. Withholding ([NEVER]) is a catalog manager's call; sharing ([SHARE], or going back to
 * [INHERIT]) sends tenant data out, so it is an integrations manager's (ADR 0048).
 */
enum class CatalogMirrorPolicy(val label: String) {
    INHERIT("Tenant's choice"),
    SHARE("Share"),
    NEVER("Never share"),
}

/** Whether one catalog's releases go to the mirror, and if not, why not. */
enum class CatalogMirrorSharing(val label: String) {
    /** Its releases are shared. */
    SHARED("Shared"),

    /** The tenant does not share catalogs (never opted in, or opted out). */
    TENANT_NOT_SHARING("Not shared: this tenant does not share catalogs"),

    /** The tenant shares only the catalogs it marks, and this one is not marked. */
    NOT_MARKED("Not shared: not marked to share"),

    /** Marked never to be shared. */
    WITHHELD("Never shared"),

    ;

    companion object {
        /**
         * The sharing rule (ADR 0051): opted in, and either marked [CatalogMirrorPolicy.SHARE] or
         * inheriting while the tenant shares every catalog. Catalogs Epistola supplies (the system
         * catalog, bundled ones) follow it like any other. [CatalogMirror.SENT_RELEASES_SQL] applies
         * the same rule in SQL, and adds what shared releases depend on.
         *
         * @param shareByDefault the tenant's choice at opt-in; null while it does not share.
         */
        fun resolve(shareByDefault: Boolean?, policy: CatalogMirrorPolicy): CatalogMirrorSharing = when {
            shareByDefault == null -> TENANT_NOT_SHARING
            policy == CatalogMirrorPolicy.NEVER -> WITHHELD
            policy == CatalogMirrorPolicy.SHARE -> SHARED
            shareByDefault -> SHARED
            else -> NOT_MARKED
        }
    }
}

/**
 * The support connection the catalog mirror shares over (ADR 0053). The mirror uses one connection
 * for now; every consent, policy, default and state row it reads or writes is that connection's.
 */
val MIRROR_CONNECTION: UUID = SupportConnections.DISCOVERED_ID

/** [MIRROR_CONNECTION] as an SQL literal, for statements built from constants. */
const val MIRROR_CONNECTION_SQL = "'${SupportConnections.DISCOVERED_ID_TEXT}'::uuid"

/**
 * SQL predicate for the mirror's rows in `support_sharing_choice` and `support_sharing_default`, which
 * hold every shared service's choices: [MIRROR_CONNECTION] and the section `mirror`. [alias] is the
 * table's alias, or empty.
 */
internal fun mirrorSharing(alias: String = ""): String {
    val p = if (alias.isEmpty()) "" else "$alias."
    return "${p}connection_id = $MIRROR_CONNECTION_SQL AND ${p}section = '${CatalogMirror.SECTION}'"
}

/** SQL expression: the mirror choice's "share every catalog" option of the row aliased [alias]. */
internal fun shareByDefault(alias: String = ""): String = "(${if (alias.isEmpty()) "" else "$alias."}options->>'shareByDefault')::boolean"

/** Constants of the mirror that the page, the commands and the reconcile share. */
object CatalogMirror {
    /**
     * The version of the statement of what leaves the installation that a person opting in agrees
     * to (the opt-in form). Raise it when that statement changes, so consent records say which one
     * was agreed to.
     */
    const val CONSENT_VERSION = "2026-11-06.2"

    /** The key of the mirror as a shared service: its sharing section and its restriction. */
    const val SECTION = "mirror"

    /** At most this many bindings per tenant (`docs/support-protocol.md`, "Reconcile a tenant"). */
    const val MAX_BINDINGS = 10_000

    /** The largest release archive the support service accepts. */
    const val MAX_ARCHIVE_BYTES = 20L * 1024 * 1024

    /** An unchanged binding set is still sent at least this often, so the copy never drifts for long. */
    val FULL_RECONCILE_INTERVAL: Duration = Duration.ofDays(1)

    /**
     * What a claim lease allows on top of the deadline of the largest upload
     * ([CatalogMirrorStateStore.claimLease]): exporting the release, and the round's database work.
     */
    val CLAIM_LEASE_MARGIN: Duration = Duration.ofMinutes(4)

    /** Back-off after a failed round is capped here. */
    val MAX_BACKOFF: Duration = Duration.ofHours(6)

    /**
     * What the tenant `:t` sends, as common table expressions ending in `sent(version_id)` for a
     * query to select from (`docs/catalog-mirror.md`, "What is sent"):
     *
     * - `chosen`: the released versions of the catalogs the sharing rule shares
     *   ([CatalogMirrorSharing.resolve]);
     * - `closure(root_id, version_id)`: each chosen release with every released version it depends
     *   on, all the way down, because Epistola cannot render a release without them;
     * - `blocked(root_id, blocker_id)`: a chosen release whose closure holds a release of a catalog
     *   marked never to be shared, and that catalog: "never share" wins, so the whole release stays
     *   home;
     * - `sent`: the closures of the chosen releases that are not blocked.
     */
    const val SENT_RELEASES_SQL = """
        WITH RECURSIVE
        chosen AS (
            SELECT v.version_id
            FROM support_sharing_choice m
            JOIN catalogs c ON c.tenant_key = m.tenant_key
            LEFT JOIN support_mirror_catalog_policies p ON p.connection_id = $MIRROR_CONNECTION_SQL AND p.tenant_key = c.tenant_key AND p.catalog_id = c.catalog_id
            JOIN catalog_versions v ON v.tenant_key = c.tenant_key AND v.catalog_id = c.catalog_id AND v.state = 'released'
            WHERE m.connection_id = $MIRROR_CONNECTION_SQL AND m.section = '$SECTION' AND m.tenant_key = :t
              AND m.withdrawn_at IS NULL
              AND (p.policy = 'SHARE' OR (p.policy IS NULL AND (m.options->>'shareByDefault')::boolean))
        ),
        closure (root_id, version_id) AS (
            SELECT version_id, version_id FROM chosen
            UNION
            SELECT cl.root_id, d.target_version_id
            FROM closure cl
            JOIN catalog_version_dependencies d ON d.tenant_key = :t AND d.version_id = cl.version_id
            JOIN catalog_versions dv ON dv.tenant_key = :t AND dv.version_id = d.target_version_id AND dv.state = 'released'
        ),
        blocked (root_id, blocker_id) AS (
            SELECT cl.root_id, bv.catalog_id
            FROM closure cl
            JOIN catalog_versions bv ON bv.tenant_key = :t AND bv.version_id = cl.version_id
            JOIN support_mirror_catalog_policies bp ON bp.connection_id = $MIRROR_CONNECTION_SQL AND bp.tenant_key = :t AND bp.catalog_id = bv.catalog_id AND bp.policy = 'NEVER'
        ),
        sent AS (
            SELECT DISTINCT cl.version_id
            FROM closure cl
            WHERE NOT EXISTS (SELECT 1 FROM blocked b WHERE b.root_id = cl.root_id)
        )
    """
}
