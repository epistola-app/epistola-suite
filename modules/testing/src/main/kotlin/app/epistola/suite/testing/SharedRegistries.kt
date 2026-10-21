// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import app.epistola.suite.common.UUIDv7
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName

/**
 * Two CNCF distribution registries (`registry:2`) per test JVM, for the catalog registry route
 * (ADR 0032): `local`, anonymous, and `secured`, behind htpasswd Basic authentication with a
 * repository prefix. Started on first use and stopped when the JVM exits (Ryuk is off locally).
 *
 * Test classes register them with [register] from a `@DynamicPropertySource`. Releases pushed by
 * one test stay for the next, so every test uses catalog keys of its own.
 */
object SharedRegistries {
    const val USERNAME = "epistola"

    /** Generated per run: nothing to leak, and nothing a committed file could carry. */
    val password: String = UUIDv7.generate().toString()

    const val SECURED_PREFIX = "team/catalogs"

    val local: GenericContainer<*> by lazy { start(registry()) }

    val secured: GenericContainer<*> by lazy {
        val htpasswd = "$USERNAME:" + BCryptPasswordEncoder().encode(password) + "\n"
        start(
            registry()
                .withCopyToContainer(Transferable.of(htpasswd), "/auth/htpasswd")
                .withEnv("REGISTRY_AUTH", "htpasswd")
                .withEnv("REGISTRY_AUTH_HTPASSWD_REALM", "Epistola test registry")
                .withEnv("REGISTRY_AUTH_HTPASSWD_PATH", "/auth/htpasswd"),
        )
    }

    fun url(container: GenericContainer<*>): String = "http://${container.host}:${container.getMappedPort(PORT)}"

    /** Configures `local` (index 0) and `secured` (index 1), and plaintext http, which both are. */
    fun register(registry: DynamicPropertyRegistry) {
        registry.add("epistola.catalog.allow-http") { "true" }
        registry.add("epistola.catalog.registries[0].name") { "local" }
        registry.add("epistola.catalog.registries[0].url") { url(local) }
        registry.add("epistola.catalog.registries[1].name") { "secured" }
        registry.add("epistola.catalog.registries[1].url") { url(secured) }
        registry.add("epistola.catalog.registries[1].repository-prefix") { SECURED_PREFIX }
        registry.add("epistola.catalog.registries[1].username") { USERNAME }
        registry.add("epistola.catalog.registries[1].password") { password }
    }

    private const val PORT = 5000

    private fun registry(): GenericContainer<*> = GenericContainer(DockerImageName.parse("registry:2"))
        .withExposedPorts(PORT)
        .waitingFor(Wait.forHttp("/v2/").forStatusCodeMatching { it == 200 || it == 401 })

    private fun start(container: GenericContainer<*>): GenericContainer<*> {
        container.start()
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { container.stop() } }, "registry-test-container-stop"))
        return container
    }
}
