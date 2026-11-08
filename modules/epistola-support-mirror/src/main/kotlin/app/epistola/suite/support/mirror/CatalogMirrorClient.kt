// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.common.sha256Hex
import app.epistola.suite.support.connection.SupportConnection
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** The support service's answer to a tenant's complete set (`docs/support-protocol.md`). */
data class MirrorReconcileResponse(val missing: List<String>, val bindings: Int, val removed: Int)

/** The support service accepted a tenant's set without saying what it now holds or lacks. */
class MirrorAnswerIncompleteException(tenantKey: String) : RuntimeException("The support service answered the set of tenant $tenantKey without 'missing' and 'bindings'")

/** A tenant's complete set as it goes on the wire, and its digest. */
class MirrorTenantSet(val body: ByteArray, val size: Int) {
    /** SHA-256 of the body: the same set always serialises to the same bytes (stable order, fixed field order). */
    val digest: String = "sha256:" + sha256Hex(body)
}

/**
 * The catalog-mirror endpoints of the support service (`docs/support-protocol.md`, "Catalog
 * mirror"), over the support connection's authorised client. Network I/O: never call it inside a
 * database transaction. Failures are the RestClient's exceptions; classify them with
 * `isSupportUnavailable()`.
 */
@Component
class CatalogMirrorClient(
    private val connection: SupportConnection,
    private val objectMapper: ObjectMapper,
) {
    /** `GET {api}/mirror/tenants`: the tenants the mirror holds anything of for this installation. */
    fun heldTenants(): List<String> {
        val body = connection.restClient().get().uri("/mirror/tenants")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .body(String::class.java)
            .orEmpty()
        if (body.isBlank()) return emptyList()
        return objectMapper.readTree(body).path("tenants").iterator().asSequence().map { it.asString() }.toList()
    }

    /**
     * `PUT {api}/mirror/tenants/{tenantKey}`: replaces what the mirror holds of the tenant with [set].
     * The answer must state `missing` and `bindings`: only an explicit `bindings: 0` confirms a
     * withdrawal, and only an explicit `missing` list says which releases to upload. An answer
     * without them (an empty body from a proxy, say) is refused rather than read as success.
     */
    fun putTenant(tenantKey: String, set: MirrorTenantSet): MirrorReconcileResponse {
        val body = connection.restClient().put().uri("/mirror/tenants/{tenant}", tenantKey)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(set.body)
            .retrieve()
            .body(String::class.java)
            .orEmpty()
        val json = if (body.isBlank()) null else objectMapper.readTree(body)
        val missing = json?.get("missing")?.takeIf { it.isArray }
        val bindings = json?.get("bindings")?.takeIf { it.isIntegralNumber }
        if (missing == null || bindings == null) throw MirrorAnswerIncompleteException(tenantKey)
        return MirrorReconcileResponse(
            missing = missing.iterator().asSequence().map { it.asString() }.toList(),
            bindings = bindings.asInt(),
            removed = json.path("removed").asInt(0),
        )
    }

    /** `PUT {api}/mirror/releases/{contentHash}`: the release archive exactly as the suite exports it. */
    fun putRelease(contentHash: String, archive: ByteArray) {
        connection.restClient().put().uri("/mirror/releases/{hash}", contentHash)
            .contentType(APPLICATION_ZIP)
            .body(archive)
            .retrieve()
            .toBodilessEntity()
    }

    /** The wire form of [bindings] (`{"bindings": [...]}`), in the order given. */
    fun tenantSet(bindings: List<MirrorBinding>): MirrorTenantSet {
        val root = objectMapper.createObjectNode()
        val array = root.putArray("bindings")
        bindings.forEach { binding -> binding(array.addObject(), binding) }
        return MirrorTenantSet(objectMapper.writeValueAsBytes(root), bindings.size)
    }

    private fun binding(node: ObjectNode, binding: MirrorBinding) {
        node.put("registry", binding.registry)
        node.put("namespace", binding.catalog.namespace)
        node.put("key", binding.catalog.key.value)
        node.put("version", binding.version)
        node.put("contentHash", binding.contentHash)
        node.put("releasedAt", binding.releasedAt.toInstant().toString())
        val deployments = node.putArray("deployments")
        binding.deployments.forEach { deployments.addObject().put("environment", it.environment).put("production", it.production) }
        val dependencies = node.putArray("dependencies")
        binding.dependencies.forEach { dependency ->
            dependencies.addObject()
                .put("registry", dependency.registry)
                .put("namespace", dependency.namespace)
                .put("key", dependency.key)
                .put("version", dependency.version)
                .put("contentHash", dependency.contentHash)
        }
    }

    companion object {
        val APPLICATION_ZIP = MediaType("application", "zip")
    }
}
