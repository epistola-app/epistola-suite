// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.registry

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * The container registries catalog releases may be published to and installed from (alpha,
 * ADR 0032 section 7): one list per installation, empty by default, which keeps the feature out of
 * sight everywhere.
 *
 * ```yaml
 * epistola:
 *   catalog:
 *     registries:
 *       - name: acme
 *         url: https://registry.acme.example
 *         repository-prefix: epistola/catalogs
 *         username: ${CATALOG_REGISTRY_USERNAME}
 *         password: ${CATALOG_REGISTRY_PASSWORD}
 * ```
 *
 * A registry is the operator's choice, not something a user types, so its address is not held to
 * the outbound source rules for private networks: an organisation's own registry is usually on its
 * own network. Plaintext `http://` is refused unless `epistola.catalog.allow-http` permits it,
 * the same switch catalog sources use; [CatalogRegistries] checks that when the application starts.
 * See `docs/catalog-registry.md`.
 */
@ConfigurationProperties(prefix = "epistola.catalog")
data class CatalogRegistryProperties(
    val registries: List<Registry> = emptyList(),
) {
    init {
        val duplicates = registries.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "epistola.catalog.registries names a registry more than once: ${duplicates.joinToString()}" }
    }

    data class Registry(
        /** What people pick and what a catalog installed from it records (`oci:<name>/…`). A lowercase slug. */
        val name: String,
        /** The registry's address, `https://host[:port]`, without a path. */
        val url: String,
        /** Repositories go under this path, `<prefix>/<namespace>/<catalogKey>`. Empty for the registry's root. */
        val repositoryPrefix: String = "",
        /** For Basic authentication, and for asking a Bearer token service. Empty for anonymous access. */
        val username: String? = null,
        /** From the environment or a secret, never from a committed file. */
        val password: String? = null,
    ) {
        init {
            require(NAME.matches(name)) { "Catalog registry name '$name' must be a lowercase slug (letters, digits, hyphens)" }
            val uri = runCatching { URI(url.trimEnd('/')) }.getOrNull()
            require(uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) {
                "Catalog registry '$name' needs a url like https://registry.example.com, not '$url'"
            }
            require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) {
                "Catalog registry '$name': give the url without a path, query or credentials ('$url'); put a path in repository-prefix"
            }
            require(RegistryRepository.isValidPrefix(repositoryPrefix.trim('/'))) {
                "Catalog registry '$name': repository-prefix '$repositoryPrefix' is not a repository path (lowercase components separated by /)"
            }
            require(password.isNullOrEmpty() || !username.isNullOrEmpty()) { "Catalog registry '$name' has a password but no username" }
        }

        /** The registry's base, `scheme://host[:port]`. */
        val baseUri: URI get() = URI(url.trimEnd('/'))

        val prefix: String get() = repositoryPrefix.trim('/')

        /** How people refer to it: the host, and the prefix when there is one. */
        val location: String get() = baseUri.authority + (if (prefix.isEmpty()) "" else "/$prefix")

        /** Never prints the password. */
        override fun toString(): String = "Registry(name=$name, url=$url, repositoryPrefix=$repositoryPrefix, username=$username, password=${if (password.isNullOrEmpty()) "none" else "****"})"
    }

    private companion object {
        val NAME = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    }
}
