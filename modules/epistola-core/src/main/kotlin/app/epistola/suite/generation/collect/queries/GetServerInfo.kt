package app.epistola.suite.generation.collect.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import java.net.InetAddress

/**
 * Server identity for the v0.3 `/ping` endpoint:
 *
 *   - serverVersion — the running suite's build version (Gradle build-info).
 *   - apiVersion    — the contract spec version this build implements.
 *   - nodeId        — opaque per-process identifier (env override or hostname).
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
    @Value("\${epistola.collect.api-version:0.3.0}")
    private val apiVersion: String,
    @Value("\${epistola.node-id:#{null}}")
    private val configuredNodeId: String?,
) : QueryHandler<GetServerInfo, ServerInfo> {

    override fun handle(query: GetServerInfo): ServerInfo {
        val serverVersion = buildProperties?.version ?: "dev"
        val nodeId = configuredNodeId
            ?: System.getenv("EPISTOLA_NODE_ID")
            ?: System.getenv("HOSTNAME")
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrElse { "unknown" }
        return ServerInfo(serverVersion = serverVersion, apiVersion = apiVersion, nodeId = nodeId)
    }
}
