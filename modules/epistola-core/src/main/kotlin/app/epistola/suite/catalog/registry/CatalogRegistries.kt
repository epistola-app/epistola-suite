// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.outbound.CatalogSourceProperties
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(CatalogRegistryProperties::class)
class CatalogRegistryConfiguration

/**
 * The registries this installation is configured with, and a client for each.
 *
 * Constructed at start-up, so a registry the installation may not contact (plaintext `http://`
 * while `epistola.catalog.allow-http` is off) stops the application rather than failing the first
 * publish.
 */
@Component
class CatalogRegistries(
    properties: CatalogRegistryProperties,
    private val sources: CatalogSourceProperties,
    private val mapper: ObjectMapper,
) {
    private val registries: Map<String, CatalogRegistryProperties.Registry> = properties.registries.associateBy { it.name }

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        // Followed by hand, so credentials stay with the registry (OciRegistryClient).
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        registries.values.forEach { registry ->
            require(registry.baseUri.scheme == "https" || sources.allowHttp) {
                "Catalog registry '${registry.name}' is plaintext http (${registry.url}); use https, or set epistola.catalog.allow-http=true for a local registry"
            }
        }
    }

    /** Whether any registry is configured: without one the feature is not offered anywhere. */
    val available: Boolean get() = registries.isNotEmpty()

    /** The configured registries, in configuration order. */
    fun all(): List<CatalogRegistryProperties.Registry> = registries.values.toList()

    /** The registry called [name]. @throws ValidationException when there is none. */
    fun named(name: String): CatalogRegistryProperties.Registry = registries[name.trim()]
        ?: throw ValidationException(
            "registry",
            if (registries.isEmpty()) {
                "No container registry is configured for this installation."
            } else {
                "There is no registry called '$name'. Configured: ${registries.keys.joinToString()}."
            },
            ValidationCode.REGISTRY_UNKNOWN,
        )

    /** A client for one piece of work against [registry]: it keeps the authorization it earns. */
    fun client(registry: CatalogRegistryProperties.Registry): OciRegistryClient = OciRegistryClient(
        registry = registry,
        http = http,
        mapper = mapper,
        allowHttp = sources.allowHttp,
        maxRedirects = sources.outbound.maxRedirects,
    )

    /**
     * Where [namespace]/[catalogKey] lives in [registry], checked: a namespace and a key as the
     * catalog protocol defines them, which are also valid repository path components.
     */
    fun repository(registry: CatalogRegistryProperties.Registry, namespace: String, catalogKey: String): String {
        val ns = namespace.trim()
        val key = catalogKey.trim()
        if (!CatalogNamespaces.isValid(ns)) {
            throw ValidationException(
                "namespace",
                "'$ns' is not a catalog namespace: a lowercase slug of ${CatalogNamespaces.MIN_LENGTH} to ${CatalogNamespaces.MAX_LENGTH} characters.",
                ValidationCode.REGISTRY_REFERENCE_INVALID,
            )
        }
        val parsedKey = runCatching { CatalogKey.of(key) }.getOrNull()
            ?: throw ValidationException("catalogKey", "'$key' is not a catalog key.", ValidationCode.REGISTRY_REFERENCE_INVALID)
        return try {
            RegistryRepository.of(registry.prefix, ns, parsedKey.value)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("catalogKey", e.message ?: "Not a repository name.", ValidationCode.REGISTRY_REFERENCE_INVALID)
        }
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}

/**
 * The source of a catalog installed from a registry, as `catalogs.source_url` records it:
 * `oci:<registry name>/<namespace>/<catalogKey>`. The registry is named, not addressed, so moving
 * it to another host is a configuration change rather than a rewrite of every catalog.
 */
data class RegistrySourceUri(val registry: String, val namespace: String, val catalogKey: String) {
    override fun toString(): String = "$SCHEME:$registry/$namespace/$catalogKey"

    companion object {
        const val SCHEME = "oci"

        fun matches(sourceUrl: String?): Boolean = sourceUrl?.startsWith("$SCHEME:") == true

        fun parse(sourceUrl: String?): RegistrySourceUri? {
            if (!matches(sourceUrl)) return null
            val parts = sourceUrl!!.removePrefix("$SCHEME:").split('/')
            if (parts.size != 3 || parts.any { it.isBlank() }) return null
            return RegistrySourceUri(parts[0], parts[1], parts[2])
        }
    }
}
