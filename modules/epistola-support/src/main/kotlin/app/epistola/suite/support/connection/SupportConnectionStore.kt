// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.crypto.CredentialCipher
import app.epistola.suite.crypto.EncryptionException
import app.epistola.suite.encryption.EncryptedCredentialColumn
import app.epistola.suite.encryption.EncryptedCredentialContributor
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * The installation's registration, encrypted at rest: whoever holds [privateJwk] is this
 * installation to the support service.
 *
 * @property issuer the support service it is registered with.
 * @property clientId null between storing a new key and the registration answering.
 * @property privateJwk the EC P-256 key as a JWK JSON string, private part included.
 * @property claimCode the current claim code; registering again issues a new one.
 */
data class StoredSupportCredentials(
    val issuer: String,
    val clientId: String? = null,
    val privateJwk: String,
    val claimCode: String? = null,
    val registeredAt: Instant? = null,
)

/** The token of a [SupportConnectionAuth.BEARER_TOKEN] connection, as stored (encrypted). */
data class StoredBearerToken(val token: String)

/** What a stored status says the connection is; maps onto [SupportConnectionState]. */
enum class StoredConnectionState {
    PENDING,
    ACTIVE,
    REFUSED,

    /** The never-activated registration is gone at the service; the credentials are forgotten. */
    EXPIRED,

    /** Epistola declined it; the credentials stay, so the service can still say so. */
    DECLINED,

    /**
     * An installation administrator disconnected: the credentials are forgotten, and the check does
     * not register again on its own until an administrator registers.
     */
    DISCONNECTED,
}

/**
 * The last check's outcome, shared by every node through the connection's row. Not secret: the claim code
 * stays with the credentials. Fields rows once had and this version no longer has are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StoredSupportStatus(
    val issuer: String? = null,
    val clientId: String? = null,
    val state: StoredConnectionState? = null,
    val scopes: Set<String> = emptySet(),
    val reason: String? = null,
    val checkedAt: Instant? = null,
    val lastError: String? = null,
    /**
     * When [scopes] were last learned from the service. A failed check moves [checkedAt] but not
     * this, so a node's newer token still counts as newer news about the scopes. Null in rows
     * written before it existed: [checkedAt] then stands in.
     */
    val scopesCheckedAt: Instant? = null,
    /** While PENDING: when the support service deletes the registration if nobody activates it. */
    val claimCodeExpiresAt: Instant? = null,
    /** While PENDING: when this registration replaced an earlier one that was gone before it was activated. */
    val replacedExpiredAt: Instant? = null,
    /** While DISCONNECTED: when, and by whom (display name; null when unknown). */
    val disconnectedAt: Instant? = null,
    val disconnectedBy: String? = null,
    /** While DISCONNECTED: how the support service took it. */
    val disconnectOutcome: SupportDisconnectOutcome? = null,
    /** While DECLINED: when Epistola declined it. */
    val declinedAt: Instant? = null,
    /** While ACTIVE: the services Epistola paused, as the last check learned them. */
    val pause: StoredSupportPause? = null,
    /**
     * The [SupportConnectionStore.version] the check read just before it learned [scopes]: a token
     * asked for at a later version is newer news about them, one asked for at it or before is not.
     * Orders the check and the tokens of every node without comparing their instants. Null in rows a
     * check did not write (and in rows written before it existed): [scopesAt] then stands in.
     */
    val scopesVersion: Long? = null,
) {
    /** When [scopes] were last learned. A function, so it is not stored. */
    fun scopesAt(): Instant? = scopesCheckedAt ?: checkedAt
}

/** [SupportPause] as stored: scope names, so a name this version does not know survives. */
data class StoredSupportPause(val scopes: Set<String> = emptySet(), val since: Instant? = null, val until: Instant? = null, val reason: String = "") {
    fun toPause(): SupportPause? = since?.let { SupportPause(scopes.mapNotNull(SupportScope::parse).toSet(), it, until, reason) }

    companion object {
        fun of(pause: SupportPause?): StoredSupportPause? = pause?.let { StoredSupportPause(it.scopes.map { s -> s.value }.toSet(), it.since, it.until, it.reason) }
    }
}

/**
 * Who Epistola contacts about this installation: the company name and administrator address an
 * installation administrator confirmed before registering. Without it, `epistola.installation.*` applies.
 */
data class StoredSupportContact(val companyName: String = "", val adminEmail: String = "", val savedAt: Instant? = null, val savedBy: String? = null)

/**
 * The support services an installation administrator selected, installation-wide. Kept apart from
 * the registration, so it survives registering again. Names this version does not know are kept.
 *
 * @property services normalised service names ([SupportScope.normalize]).
 * @property savedBy the administrator's display name, for the page; null when unknown.
 */
data class StoredSupportServices(
    val services: List<String> = emptyList(),
    val savedAt: Instant? = null,
    val savedBy: String? = null,
)

/**
 * The services selected now, and where the selection comes from.
 *
 * @property services normalised names, unknown ones included.
 * @property fromConfiguration true while no administrator saved a selection: `epistola.support.services` applies.
 */
data class SupportServiceSelection(val services: List<String>, val fromConfiguration: Boolean, val savedAt: Instant?, val savedBy: String?)

/**
 * The scopes the support service refused (`403 insufficient_scope`) on any node, shared by every node.
 *
 * @property refusals per service name, the [SupportConnectionStore.version] the refusal raised it to:
 *   a token asked for at that version or later was asked for after the refusal was stored, so its
 *   scopes are newer news; one asked for earlier may predate the withdrawal.
 */
data class StoredSupportRefusals(val refusals: Map<String, Long> = emptyMap())

/** The stored registration exists but cannot be read: its key was lost or rotated away, or it is damaged. */
class UnreadableSupportCredentialsException(cause: Throwable) : RuntimeException("The stored support registration cannot be read: ${cause.message}", cause)

/**
 * Reads and writes one support connection's row in `support_connections` (ADR 0053), and serialises
 * the work that changes it across nodes.
 *
 * Every write also raises [version], a counter in the same row: a node that reads [version] before
 * the rest and finds it unchanged knows its copy is current. That is how a change on one node reaches
 * every other node at once, with one primary-key read instead of a broadcast (ADR 0050).
 *
 * @property connectionId the connection this store is for.
 *
 * Open, with [services] and [refusals] open, so a test can stand in a store that holds or fails a read.
 */
open class SupportConnectionStore(
    private val jdbi: Jdbi,
    private val cipher: CredentialCipher,
    private val objectMapper: ObjectMapper,
    val connectionId: UUID,
) {
    /**
     * The stored registration, or null when there is none.
     *
     * @throws UnreadableSupportCredentialsException when it exists but cannot be decrypted or read.
     */
    fun credentials(): StoredSupportCredentials? {
        val envelope = column("credentials") ?: return null
        return try {
            objectMapper.readValue(cipher.decrypt(envelope), StoredSupportCredentials::class.java)
        } catch (e: EncryptionException) {
            throw UnreadableSupportCredentialsException(e)
        } catch (e: JacksonException) {
            throw UnreadableSupportCredentialsException(e)
        }
    }

    fun saveCredentials(credentials: StoredSupportCredentials) = write("credentials = :value", cipher.encrypt(objectMapper.writeValueAsString(credentials)))

    /**
     * The token a [SupportConnectionAuth.BEARER_TOKEN] connection signs in with, or null when none is
     * set. Kept encrypted in the same column as a registration, so credential rotation covers it.
     *
     * @throws UnreadableSupportCredentialsException when it exists but cannot be decrypted or read.
     */
    fun bearerToken(): String? {
        val envelope = column("credentials") ?: return null
        return try {
            objectMapper.readValue(cipher.decrypt(envelope), StoredBearerToken::class.java).token
        } catch (e: EncryptionException) {
            throw UnreadableSupportCredentialsException(e)
        } catch (e: JacksonException) {
            throw UnreadableSupportCredentialsException(e)
        }
    }

    /** Sets the token a [SupportConnectionAuth.BEARER_TOKEN] connection signs in with. */
    fun saveBearerToken(token: String) = write("credentials = :value", cipher.encrypt(objectMapper.writeValueAsString(StoredBearerToken(token))))

    fun status(): StoredSupportStatus? = json("status", StoredSupportStatus::class.java)

    fun saveStatus(status: StoredSupportStatus) = writeJson("status", status)

    /**
     * Records a failed check in one statement: [checkedAt], [lastError] and, when known, [issuer] are
     * merged into the stored status, and everything else is left as it is now. A refusal another node
     * stored while the check ran (its token requests do not take the cluster lock) is kept, and when
     * the scopes were last learned stays as it was ([StoredSupportStatus.scopesAt]).
     */
    fun noteCheckFailed(issuer: String?, checkedAt: Instant, lastError: String) {
        val patch = objectMapper.writeValueAsString(
            buildMap {
                put("checkedAt", checkedAt)
                put("lastError", lastError)
                issuer?.let { put("issuer", it) }
            },
        )
        write(
            """
            status = COALESCE(status, '{}'::jsonb)
                || jsonb_strip_nulls(jsonb_build_object('scopesCheckedAt', COALESCE(status -> 'scopesCheckedAt', status -> 'checkedAt')))
                || :value::jsonb
            """.trimIndent(),
            patch,
        )
    }

    /** The saved selection of services; null until an installation administrator saves one. */
    open fun services(): StoredSupportServices? = json("services", StoredSupportServices::class.java)

    fun saveServices(services: StoredSupportServices) = writeJson("services", services)

    /** The contact an administrator confirmed before registering, or null. */
    fun contact(): StoredSupportContact? = json("contact", StoredSupportContact::class.java)

    fun saveContact(contact: StoredSupportContact) = writeJson("contact", contact)

    /** Drops the saved selection, so `epistola.support.services` applies again. */
    fun forgetServices() = write("services = NULL")

    /**
     * Records [status] (DISCONNECTED) and forgets the registration, in one write: a node that reads
     * the status treats the connection as disconnected, and finds no credentials with it.
     */
    fun disconnected(status: StoredSupportStatus) = write("status = :value::jsonb, credentials = NULL", objectMapper.writeValueAsString(status))

    /** Forgets the registration and its status, so the next check registers anew with a new key. The service selection stays. */
    fun forget() = write("credentials = NULL, status = NULL")

    /** The refusals every node shares; none when there are none or the column cannot be read as such. */
    open fun refusals(): StoredSupportRefusals = StoredSupportRefusals(
        column("refusals")?.let { objectMapper.readTree(it) }?.properties()
            ?.mapNotNull { (scope, seq) -> seq.takeIf { it.isNumber }?.let { scope to it.asLong() } }?.toMap().orEmpty(),
    )

    /**
     * Stores that the support service refused [scope], raising [version] in the same statement, and
     * returns the version it raised it to: the refusal's place in the order of every node's tokens.
     */
    fun recordRefusal(scope: String): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery(
            """
            UPDATE support_connections
            SET version = version + 1, refusals = refusals || jsonb_build_object(:scope, version + 1), updated_at = NOW()
            WHERE id = :id
            RETURNING version
            """,
        ).bind("id", connectionId).bind("scope", scope).mapTo(Long::class.java).one()
    }

    /**
     * Drops the refusals of [scopes] stored at version [upTo] or before: what was learned since
     * settles them. A refusal stored later stays. True when one was dropped.
     */
    fun clearRefusals(upTo: Long, scopes: Collection<String>): Boolean {
        if (scopes.isEmpty()) return false
        val cleared = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE support_connections
                SET refusals = COALESCE(
                        (SELECT jsonb_object_agg(e.key, e.value) FROM jsonb_each(refusals) AS e(key, value)
                         WHERE NOT (e.key = ANY(:scopes) AND (e.value #>> '{}')::bigint <= :upTo)),
                        '{}'::jsonb),
                    version = version + 1, updated_at = NOW()
                WHERE id = :id
                  AND EXISTS (SELECT 1 FROM jsonb_each(refusals) AS e(key, value)
                              WHERE e.key = ANY(:scopes) AND (e.value #>> '{}')::bigint <= :upTo)
                """,
            ).bind("id", connectionId).bind("upTo", upTo).bindArray("scopes", String::class.java, scopes.toList()).execute()
        }
        return cleared > 0
    }

    /**
     * Stores that node [nodeId]'s clock is off ([SupportNodeClockException]), with [message], stamped
     * by the database's clock (the node's own is the one in doubt). Entries older than a day, of
     * nodes that went away while their clock was off, are dropped on the way, so the column stays small.
     */
    fun recordNodeClockError(nodeId: String, message: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE support_connections
                SET node_clocks = COALESCE(
                        (SELECT jsonb_object_agg(e.key, e.value) FROM jsonb_each(node_clocks) AS e(key, value)
                         WHERE e.key <> :node AND (e.value ->> 'at')::timestamptz > clock_timestamp() - interval '1 day'),
                        '{}'::jsonb) || jsonb_build_object(:node, jsonb_build_object('message', :message, 'at', clock_timestamp())),
                    version = version + 1, updated_at = NOW()
                WHERE id = :id
                """,
            ).bind("id", connectionId).bind("node", nodeId).bind("message", message).execute()
        }
    }

    /** Drops node [nodeId]'s clock error; true when there was one. */
    fun clearNodeClockError(nodeId: String): Boolean = jdbi.withHandle<Int, Exception> { handle ->
        handle.createUpdate(
            "UPDATE support_connections SET node_clocks = node_clocks - :node, version = version + 1, updated_at = NOW() WHERE id = :id AND jsonb_exists(node_clocks, :node)",
        ).bind("id", connectionId).bind("node", nodeId).execute()
    } > 0

    /**
     * The nodes whose clock is off, with the message each stored, by node id: only entries stored
     * within [NODE_CLOCK_ERROR_FRESH] by the database's clock. A node whose clock stays off stores its
     * entry again at least every five minutes; one that went away, or was set right but could not
     * clear its entry, drops off on its own.
     */
    fun nodeClockErrors(): Map<String, String> = jdbi.withHandle<Map<String, String>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT e.key AS node, e.value ->> 'message' AS message
            FROM support_connections c, jsonb_each(c.node_clocks) AS e(key, value)
            WHERE c.id = :id
              AND (e.value ->> 'at')::timestamptz > clock_timestamp() - make_interval(secs => :freshSeconds)
            ORDER BY e.key
            """,
        ).bind("id", connectionId).bind("freshSeconds", NODE_CLOCK_ERROR_FRESH.seconds)
            .map { rs, _ -> rs.getString("node") to rs.getString("message") }
            .list()
            .toMap(LinkedHashMap())
    }

    /** The version of this connection's row: raised by every write, never lowered. 0 before the first write. */
    fun version(): Long = jdbi.withHandle<Long, Exception> { handle ->
        handle.createQuery("SELECT version FROM support_connections WHERE id = :id").bind("id", connectionId)
            .mapTo(Long::class.java).findOne().orElse(0L)
    }

    /**
     * Runs [block] while holding a cluster-wide lock for this connection, so only one node registers
     * or checks it at a time, and returns its result; returns null at once, without running it, when
     * another node or thread holds the lock. A session-level advisory lock on a connection with no
     * transaction open: [block] talks to the support service, which must never happen inside a
     * database transaction. Nothing waits for the lock: a check or registration already running is
     * the one that counts, and a page or task that finds it taken says so instead of hanging behind it.
     */
    fun <T : Any> tryExclusively(block: () -> T): T? = jdbi.withHandle<T?, Exception> { handle ->
        val locked = handle.createQuery("SELECT pg_try_advisory_lock(:key)").bind("key", lockKey()).mapTo(Boolean::class.java).one()
        if (!locked) return@withHandle null
        try {
            block()
        } finally {
            handle.createQuery("SELECT pg_advisory_unlock(:key)").bind("key", lockKey()).mapTo(Boolean::class.java).one()
        }
    }

    /** This connection's advisory lock: [LOCK_KEY] for the discovered connection, so it stays what it was; derived from the id otherwise. */
    fun lockKey(): Long = if (connectionId == SupportConnections.DISCOVERED_ID) LOCK_KEY else LOCK_KEY xor connectionId.leastSignificantBits

    private fun column(name: String): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery("SELECT $name::text FROM support_connections WHERE id = :id").bind("id", connectionId)
            .mapTo(String::class.java).findOne().orElse(null)
    }

    private fun <T : Any> json(name: String, type: Class<T>): T? = column(name)?.let { objectMapper.readValue(it, type) }

    private fun writeJson(name: String, value: Any) = write("$name = :value::jsonb", objectMapper.writeValueAsString(value))

    /** Applies [set] to the row and raises [version] in the same statement. */
    private fun write(set: String, value: String? = null) {
        jdbi.useHandle<Exception> { handle ->
            val update = handle.createUpdate("UPDATE support_connections SET $set, version = version + 1, updated_at = NOW() WHERE id = :id").bind("id", connectionId)
            if (value != null) update.bind("value", value)
            check(update.execute() == 1) { "Support connection $connectionId does not exist" }
        }
    }

    companion object {
        /** How long a node's stored clock error counts without being stored again ([nodeClockErrors]). */
        val NODE_CLOCK_ERROR_FRESH: java.time.Duration = java.time.Duration.ofMinutes(15)

        /** "EpSuppC1": the advisory lock that keeps registration and checks of a connection to one node at a time. */
        const val LOCK_KEY = 0x4570_5375_7070_4331L
    }
}

/** Puts every connection's registration under credential rotation (`docs/encryption.md`). */
@Component
class SupportConnectionCredentials : EncryptedCredentialContributor {
    override fun columns(): Set<EncryptedCredentialColumn> = setOf(EncryptedCredentialColumn("support_connections", "credentials", listOf("id")))
}
