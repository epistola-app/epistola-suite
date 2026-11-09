// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportConnectionState
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.connection.SupportUnavailableException
import app.epistola.suite.support.connection.isInsufficientScope
import app.epistola.suite.support.privacy.TenantReferences
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestAssetContent
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.sync.ExternalCommentRef
import app.epistola.suite.support.requests.sync.ExternalUpdate
import app.epistola.suite.support.requests.sync.ExternalUpdatePage
import app.epistola.suite.support.requests.sync.SupportRequestSyncNotReadyException
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import app.epistola.suite.support.requests.sync.SyncResult
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * [SupportRequestSyncPort] over the support connection: the Feedback section of
 * `docs/support-protocol.md`, under `epistola_requests_endpoint` with the `requests` scope (protocol v2).
 *
 * Every write carries the suite's own id, so a repeat after a crash or a lost response is harmless.
 * Failures are classified for the drivers: an unreachable or overloaded service (I/O, 5xx, 429) is
 * [app.epistola.suite.support.connection.isSupportUnavailable]; a token without the `requests`
 * scope (`403 insufficient_scope`) or one still refused after the client's single 401 retry is a
 * [SupportRequestSyncNotReadyException]. Both mean "later", so no attempt is counted.
 *
 * A `@Component`, not a `@Bean` method, so the no-op fallback's `@ConditionalOnMissingBean` sees it.
 * The calls do network I/O: the drivers make them outside any database transaction.
 */
@Component
@ConditionalOnProperty(name = ["epistola.support.enabled"], havingValue = "true")
class RestSupportRequestSyncAdapter(
    private val connection: SupportConnection,
    private val objectMapper: ObjectMapper,
    private val references: TenantReferences? = null,
    private val jdbi: Jdbi? = null,
) : SupportRequestSyncPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The support connection is on; whether it takes feedback now is [isReady]. */
    override fun isEnabled(): Boolean = connection.status().state !is SupportConnectionState.Disabled

    /** The `requests` scope is granted now. Re-read on every call, so a revoked scope stops sending. */
    override fun isReady(): Boolean = connection.hasScope(SupportScope.REQUESTS)

    override fun createTicket(supportRequest: SupportRequest): SyncResult {
        val wireKey = references?.onWire(supportRequest.tenantKey) ?: supportRequest.tenantKey.value
        // Confidential tenants (ADR 0052 §1): the tenant's paths in what the browser recorded name the reference instead.
        val hide = { text: String -> confidentialPaths(text, supportRequest.tenantKey.value, wireKey) }
        val body = objectMapper.createObjectNode().apply {
            put("title", fitChars(supportRequest.title, MAX_TITLE_CHARS))
            put("kind", supportRequest.category.name)
            put("requestedPriority", (supportRequest.requestedPriority ?: supportRequest.priority).name)
            put("status", supportRequest.status.name)
            supportRequest.sourceUrl?.let { put("sourceUrl", fitChars(hide(it), MAX_SOURCE_URL_CHARS)) }
            fitConsoleLogs(supportRequest.consoleLogs?.let(hide))?.let { put("consoleLogs", it) }
            // The suite's own log lines may name the tenant anywhere in their text, not only in a path.
            fitConsoleLogs(supportRequest.serverLogs?.let { confidentialText(it, supportRequest.tenantKey.value, wireKey) })?.let { put("serverLogs", it) }
            fitMetadata(supportRequest.metadata?.let(hide))?.let { put("metadata", it) }
            put("createdAt", supportRequest.createdAt.toInstant().toString())
        }
        val requests = requestsEndpoint()
        val response = call("sending support request ${supportRequest.id}") {
            connection.restClient().put()
                .uri("$requests/{tenantKey}/{suiteRequestId}", wireKey, supportRequest.id.value)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String::class.java)
        }
        val node = objectMapper.readTree(response.orEmpty())
        val remoteId = node.text("requestId") ?: throw IllegalStateException("The support service answered without a requestId")
        // The description is the request's question: the message with the request's own id, so a
        // repeat after a lost answer is the same message.
        val question = objectMapper.createObjectNode().apply {
            put("kind", "QUESTION")
            put("body", fitText(supportRequest.description))
            put("createdAt", supportRequest.createdAt.toInstant().toString())
        }
        putMessage(remoteId, questionId(supportRequest), question, "sending the question of support request ${supportRequest.id}")
        return SyncResult(externalRef = remoteId, externalUrl = node.text("url").orEmpty())
    }

    override fun uploadAsset(supportRequest: SupportRequest, assetKey: SupportRequestAssetKey, asset: SupportRequestAssetContent) {
        val supportId = supportRequest.supportId()
        val contentType = asset.contentType.lowercase()
        require(contentType in ASSET_TYPES) { "The support service takes no $contentType asset, only ${ASSET_TYPES.joinToString()}" }
        require(asset.content.size <= MAX_ASSET_BYTES) { "Asset $assetKey is ${asset.content.size} bytes, over the support service's $MAX_ASSET_BYTES" }
        val requests = requestsEndpoint()
        call("sending attachment $assetKey of support request ${supportRequest.id}") {
            connection.restClient().put()
                .uri("$requests/{requestId}/attachments/{suiteAttachmentId}", supportId, assetKey.value)
                .contentType(MediaType.parseMediaType(contentType))
                .headers { headers -> asset.filename?.takeIf { SAFE_FILENAME.matches(it) }?.let { headers.set(FILENAME_HEADER, it) } }
                .body(asset.content)
                .retrieve()
                .toBodilessEntity()
        }
    }

    override fun addComment(supportRequest: SupportRequest, comment: SupportRequestComment): ExternalCommentRef {
        val body = objectMapper.createObjectNode().apply {
            put("kind", "REPLY")
            put("authorName", fitChars(comment.authorName, MAX_AUTHOR_NAME_CHARS))
            comment.authorEmail?.takeIf { it.length <= MAX_AUTHOR_EMAIL_CHARS }?.let { put("authorEmail", it) }
            put("body", fitText(comment.body))
            put("createdAt", comment.createdAt.toInstant().toString())
        }
        val messageId = putMessage(supportRequest.supportId(), comment.id.value, body, "sending comment ${comment.id} of support request ${supportRequest.id}")
        return ExternalCommentRef(messageId)
    }

    /**
     * The status as a STATUS message. Its id is derived from the request, the status and when it was
     * set, so a repeat of the same change is the same message and a later change a new one.
     */
    override fun updateStatus(supportRequest: SupportRequest, status: SupportRequestStatus) {
        val body = objectMapper.createObjectNode().put("kind", "STATUS").put("status", status.name)
        val id = UUID.nameUUIDFromBytes("status:${supportRequest.id.value}:${status.name}:${supportRequest.updatedAt.toInstant()}".toByteArray())
        putMessage(supportRequest.supportId(), id, body, "sending the status of support request ${supportRequest.id}")
    }

    override fun fetchUpdates(acknowledgeUpTo: Long?): ExternalUpdatePage {
        val requests = requestsEndpoint()
        // The service keeps where this installation is: it acknowledges what it handled and gets
        // what comes after (docs/support-protocol.md, "Changes from Epistola").
        val body = objectMapper.createObjectNode().apply {
            acknowledgeUpTo?.let { put("acknowledgeUpTo", it) }
            put("limit", UPDATES_PAGE_SIZE)
        }
        val response = call("reading support request changes") {
            connection.restClient().post()
                .uri("$requests/changes")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String::class.java)
        }
        val node = objectMapper.readTree(response.orEmpty())
        val changes = node.path("changes").takeIf { it.isArray }
        val updates = changes?.mapNotNull(::toUpdate).orEmpty()
        val lastInPage = changes?.mapNotNull { it.path("sequence").takeIf(JsonNode::isNumber)?.asLong() }?.maxOrNull()
        val lastSequence = node.path("lastSequence").takeIf(JsonNode::isNumber)?.asLong() ?: lastInPage
        return ExternalUpdatePage(updates = updates, lastSequence = lastSequence, hasMore = node.path("hasMore").asBoolean(false))
    }

    /** One entry of the changes feed; null (logged) for one this suite does not understand. */
    private fun toUpdate(node: JsonNode): ExternalUpdate? {
        val seq = node.path("sequence").takeIf(JsonNode::isNumber)?.asLong()
        val remoteId = node.text("requestId")
        // The request's own tenant, found by the suite's id: the service names it by its reference
        // while tenants are confidential, and by the key it was sent under before that.
        val tenantKey = node.text("suiteRequestId")?.let(::localTenant)
            ?: node.text("tenantKey")?.let { runCatching { TenantKey.of(it) }.getOrNull() }
        if (seq == null || remoteId == null || tenantKey == null) {
            log.warn("Skipping a support request change without a usable sequence, requestId or tenantKey: {}", node)
            return null
        }
        val occurredAt = node.text("occurredAt")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
        return when (node.text("type")) {
            "MESSAGE" -> {
                val message = node.path("message")
                val messageId = message.text("messageId")
                val body = message.text("body")
                if (messageId == null || body == null || message.text("kind") != "REPLY") {
                    log.warn("Skipping support request change {}: not a reply with a messageId and a body", seq)
                    return null
                }
                ExternalUpdate.Comment(
                    seq = seq,
                    tenantKey = tenantKey,
                    externalRef = remoteId,
                    occurredAt = occurredAt,
                    externalCommentId = messageId,
                    authorName = message.text("authorName") ?: "Epistola support",
                    authorEmail = message.text("authorEmail"),
                    body = body,
                )
            }

            "STATUS" -> {
                val status = node.text("status")?.let { value -> SupportRequestStatus.entries.firstOrNull { it.name == value } }
                if (status == null) {
                    log.warn("Skipping support request change {}: unknown status {}", seq, node.text("status"))
                    return null
                }
                ExternalUpdate.StatusChange(seq = seq, tenantKey = tenantKey, externalRef = remoteId, occurredAt = occurredAt, newStatus = status)
            }

            "PRIORITY" -> {
                val priority = node.text("priority")?.let { value -> SupportRequestPriority.entries.firstOrNull { it.name == value } }
                if (priority == null) {
                    log.warn("Skipping support request change {}: unknown priority {}", seq, node.text("priority"))
                    return null
                }
                ExternalUpdate.PriorityChange(seq = seq, tenantKey = tenantKey, externalRef = remoteId, occurredAt = occurredAt, newPriority = priority)
            }

            else -> {
                log.debug("Skipping support request change {} of a type this suite does not know: {}", seq, node.text("type"))
                null
            }
        }
    }

    /** Puts one message on the request the service knows as [remoteId]; the service's message id. */
    private fun putMessage(remoteId: String, messageId: UUID, body: JsonNode, what: String): String {
        val requests = requestsEndpoint()
        val response = call(what) {
            connection.restClient().put()
                .uri("$requests/{requestId}/messages/{suiteMessageId}", remoteId, messageId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(String::class.java)
        }
        return objectMapper.readTree(response.orEmpty()).text("messageId")
            ?: throw IllegalStateException("The support service answered without a messageId")
    }

    /** `epistola_requests_endpoint`; a service that names none cannot take support requests now. */
    private fun requestsEndpoint(): String = connection.requestsEndpoint()?.toString()?.trimEnd('/')
        ?: throw SupportUnavailableException("The support service names no requests endpoint")

    /** The question's id: the request's own, as the question message here carries it, so a repeat is the same message. */
    private fun questionId(supportRequest: SupportRequest): UUID = supportRequest.id.value

    private fun localTenant(suiteSupportRequestId: String): TenantKey? {
        val id = runCatching { java.util.UUID.fromString(suiteSupportRequestId) }.getOrNull() ?: return null
        return jdbi?.withHandle<String?, Exception> { handle ->
            handle.createQuery("SELECT tenant_key FROM support_requests WHERE id = :id").bind("id", id).mapTo(String::class.java).findOne().orElse(null)
        }?.let(TenantKey::of)
    }

    private fun SupportRequest.supportId(): String = requireNotNull(externalRef) { "Support request $id has not been sent to Epistola support yet" }

    /**
     * Runs one call and turns "this token may not do that" into [SupportRequestSyncNotReadyException]:
     * `403 insufficient_scope` (pending, or the scope was taken away — reported to the connection,
     * so the drivers stop asking) and a 401 that survived the client's retry with a fresh token.
     * Every other failure passes through unchanged.
     */
    private fun <T> call(what: String, block: () -> T): T = try {
        block()
    } catch (e: HttpClientErrorException) {
        when {
            e.isInsufficientScope() -> {
                // Epistola withdrew it: support requests read as not ready on this node until a check finds it granted again.
                connection.scopeRefused(SupportScope.REQUESTS)
                throw SupportRequestSyncNotReadyException("Failed $what: the token has no requests scope", e)
            }

            e.statusCode.value() == 401 -> throw SupportRequestSyncNotReadyException("Failed $what: the support service refused the token", e)

            else -> throw e
        }
    }

    private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isString }?.stringValue()

    /**
     * The console log within the protocol's limit. It is a JSON array of entries, oldest first, so
     * when it is too long the oldest entries go; anything else too long is left out.
     */
    internal fun fitConsoleLogs(raw: String?): String? {
        if (raw == null || raw.utf8Size() <= MAX_CONSOLE_LOG_BYTES) return raw
        val entries = runCatching { objectMapper.readTree(raw) }.getOrNull() as? ArrayNode ?: return null
        val kept = ArrayDeque<JsonNode>()
        var size = 2 // the brackets
        for (entry in entries.reversed()) {
            val entrySize = objectMapper.writeValueAsString(entry).utf8Size() + 1 // the comma
            if (size + entrySize > MAX_CONSOLE_LOG_BYTES) break
            kept.addFirst(entry)
            size += entrySize
        }
        return objectMapper.writeValueAsString(objectMapper.createArrayNode().apply { kept.forEach(::add) })
    }

    /**
     * [value] within [maxChars] UTF-16 characters, the protocol's count: cut with a trailing `…`,
     * never in the middle of a surrogate pair.
     */
    internal fun fitChars(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        var end = maxChars - 1
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end) + "…"
    }

    /**
     * A description or comment body within [MAX_TEXT_BYTES] of UTF-8: the start is kept and
     * [CUT_MARKER] says the rest was left out. The full text stays in this suite.
     */
    internal fun fitText(value: String): String {
        if (value.utf8Size() <= MAX_TEXT_BYTES) return value
        val budget = MAX_TEXT_BYTES - CUT_MARKER.utf8Size()
        var bytes = 0
        var end = 0
        while (end < value.length) {
            val codePoint = value.codePointAt(end)
            val size = String(Character.toChars(codePoint)).utf8Size()
            if (bytes + size > budget) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return value.substring(0, end) + CUT_MARKER
    }

    /** [text] with every `/tenants/<tenantKey>` path naming [wireKey] instead; unchanged when they are the same. */
    internal fun confidentialPaths(text: String, tenantKey: String, wireKey: String): String = if (tenantKey == wireKey) text else text.replace(Regex("/tenants/" + Regex.escape(tenantKey) + "(?=[/?#\"\\s]|$)"), "/tenants/$wireKey")

    /**
     * [text] with every whole-word [tenantKey] naming [wireKey] instead; unchanged when they are the same.
     * A word that happens to equal the key is replaced too: that only ever hides more.
     */
    internal fun confidentialText(text: String, tenantKey: String, wireKey: String): String = if (tenantKey == wireKey) {
        text
    } else {
        text.replace(Regex("(?<![A-Za-z0-9_-])" + Regex.escape(tenantKey) + "(?![A-Za-z0-9_-])"), wireKey)
    }

    /** The browser metadata, or nothing when it is over the protocol's limit. */
    internal fun fitMetadata(raw: String?): String? = raw?.takeIf { it.utf8Size() <= MAX_METADATA_BYTES }

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

    companion object {
        /** `consoleLogs` is at most 256 KiB (docs/support-protocol.md). */
        const val MAX_CONSOLE_LOG_BYTES = 256 * 1024

        /** `metadata` is at most 16 KiB. */
        const val MAX_METADATA_BYTES = 16 * 1024

        /** `title` is at most 1000 characters. */
        const val MAX_TITLE_CHARS = 1000

        /** `description` and a comment's `body` are at most 256 KiB of UTF-8. */
        const val MAX_TEXT_BYTES = 256 * 1024

        /** `sourceUrl` is at most 4096 characters. */
        const val MAX_SOURCE_URL_CHARS = 4096

        /** A comment's `authorName` is at most 200 characters, its `authorEmail` at most 320. */
        const val MAX_AUTHOR_NAME_CHARS = 200
        const val MAX_AUTHOR_EMAIL_CHARS = 320

        /** Ends a description or comment that was cut to fit. */
        const val CUT_MARKER = "\n\n[… cut to fit the support service's limit]"

        /** An asset is at most 2 MiB. */
        const val MAX_ASSET_BYTES = 2 * 1024 * 1024

        /** The asset types the support service takes. */
        val ASSET_TYPES = setOf("image/png", "image/jpeg", "image/webp")

        const val FILENAME_HEADER = "X-Epistola-Filename"

        /** Updates per page asked for; the protocol allows up to 500. */
        const val UPDATES_PAGE_SIZE = 100

        private val SAFE_FILENAME = Regex("[A-Za-z0-9._-]{1,255}")
    }
}
