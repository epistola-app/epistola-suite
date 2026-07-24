// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.security.SystemInternal
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import java.io.File
import java.util.jar.JarFile

/**
 * Server identity for the v0.3 `/ping` endpoint:
 *
 *   - serverVersion — the running suite's build version (Gradle build-info).
 *   - apiVersion    — the contract spec version this build implements,
 *                     derived from the contract JAR's `Implementation-Version`
 *                     manifest entry. The contract is the source of truth;
 *                     hardcoding it in the suite would be one place to forget
 *                     to bump on every contract upgrade.
 *   - nodeId        — opaque per-process identifier (see [NodeIdentity]).
 *
 * SystemInternal — `/ping` may be called unauthenticated, and even when
 * authenticated the response is the same. There's no tenant or user context.
 */
class GetServerInfo :
    Query<ServerInfo>,
    SystemInternal

data class ServerInfo(
    val serverVersion: String,
    val apiVersion: String,
    val nodeId: String,
)

@Component
class GetServerInfoHandler(
    // BuildProperties is bean-conditional on `build-info.properties` existing
    // (it does in the production build but may be absent in unit-test contexts).
    // Optional injection keeps unit tests from needing the full build.
    private val buildProperties: BuildProperties? = null,
    private val nodeIdentity: NodeIdentity,
) : QueryHandler<GetServerInfo, ServerInfo> {

    override fun handle(query: GetServerInfo): ServerInfo {
        val serverVersion = buildProperties?.version ?: "dev"
        return ServerInfo(serverVersion = serverVersion, apiVersion = contractVersion, nodeId = nodeIdentity.nodeId)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(GetServerInfoHandler::class.java)

        /**
         * The Epistola contract spec version exposed via `/ping`. Resolved once
         * from the contract JAR's manifest — see [resolveContractVersion].
         */
        val contractVersion: String by lazy { resolveContractVersion() }

        /**
         * Probe the classpath for the contract version, in two passes:
         *
         *  1. `Package.getImplementationVersion()` on a class from the contract
         *     package. Works when the JVM has loaded the contract from a JAR
         *     whose manifest carries `Implementation-Version` (the standard
         *     Maven convention; the contract publishes this).
         *  2. Fallback: open the JAR file containing the contract class and
         *     read `META-INF/MANIFEST.MF` directly. Catches edge cases where
         *     the package object has not been wired with manifest metadata.
         *
         * Returns `"unknown"` if both passes fail. That's a strong enough
         * signal in `/ping` for an operator to spot a misbuilt classpath
         * (e.g. running from a directory output rather than the published JAR).
         */
        private fun resolveContractVersion(): String {
            val probeClassName = "app.epistola.api.SystemApi"
            val probeClass = runCatching { Class.forName(probeClassName) }.getOrNull()
            if (probeClass == null) {
                logger.warn("Contract probe class {} not on classpath; apiVersion=\"unknown\"", probeClassName)
                return "unknown"
            }
            probeClass.`package`?.implementationVersion
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            return readVersionFromJarManifest(probeClass) ?: run {
                logger.warn(
                    "Contract JAR for {} carries no Implementation-Version manifest entry; apiVersion=\"unknown\"",
                    probeClassName,
                )
                "unknown"
            }
        }

        private fun readVersionFromJarManifest(probeClass: Class<*>): String? = runCatching {
            val location = probeClass.protectionDomain?.codeSource?.location?.toURI() ?: return@runCatching null
            if (location.scheme != "file") return@runCatching null
            val file = File(location)
            if (!file.isFile) return@runCatching null // exploded class dir, not a JAR
            JarFile(file).use { jar ->
                jar.manifest?.mainAttributes?.getValue("Implementation-Version")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
