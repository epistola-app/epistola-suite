package app.epistola.suite.support

import app.epistola.hub.client.EpistolaHubClient
import app.epistola.hub.client.HubRegistrationRetryLoop
import app.epistola.hub.client.InstallationMetadata
import app.epistola.hub.client.discovery.DEFAULT_DISCOVERY_URL
import app.epistola.hub.client.discovery.HubDiscovery
import app.epistola.hub.client.discovery.HubEndpoint
import app.epistola.hub.client.port.InstallationStore
import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.metadata.AppMetadataService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.info.BuildProperties
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn

/**
 * Wires the epistola-hub integration. Active only when
 * `epistola.support.enabled=true`; OSS deployments keep this module on the
 * classpath but never construct any of these beans.
 */
@Configuration
@EnableConfigurationProperties(SupportProperties::class)
@ConditionalOnProperty(
    prefix = "epistola.support",
    name = ["enabled"],
    havingValue = "true",
)
class SupportConfiguration {
    @Bean
    fun installationStore(metadata: AppMetadataService): InstallationStore = AppMetadataInstallationStore(metadata)

    /**
     * Resolved during context refresh. `installations.get()` reads
     * `app_metadata`, so we must wait for Flyway. Spring Boot adds
     * implicit depends-on relationships for `JdbcOperations`, but not for
     * JDBI, hence the explicit `@DependsOn`.
     */
    @Bean
    @DependsOn("flywayInitializer")
    fun installationMetadata(
        installations: InstallationService,
        installationProperties: InstallationProperties,
        buildProperties: BuildProperties?,
    ): InstallationMetadata {
        val ip = installationProperties
        require(ip.companyName.isNotBlank()) {
            "epistola.installation.companyName must be set when epistola.support.enabled=true"
        }
        require(ip.adminEmail.isNotBlank()) {
            "epistola.installation.adminEmail must be set when epistola.support.enabled=true"
        }
        require(ip.environment.isNotBlank()) {
            "epistola.installation.environment must be set when epistola.support.enabled=true"
        }
        val installation = installations.get()
        return InstallationMetadata(
            installationId = installation.id,
            name = ip.resolvedName(),
            description = ip.description,
            suiteVersion = buildProperties?.version ?: "dev",
            tenants = emptyList(),
            enabledFeatures = emptyList(),
        )
    }

    /**
     * Endpoint resolution. When `epistola.support.hub.host` is set, the
     * static discovery skips `.well-known` entirely and goes straight to
     * the configured host/port (use for local dev or cluster-internal
     * deployments). Otherwise, fall back to fetching `.well-known` from
     * the configured discovery URL (or the SaaS default).
     */
    @Bean
    fun hubDiscovery(props: SupportProperties): HubDiscovery {
        val hub = props.hub
        if (hub.host.isNotBlank()) {
            require(hub.port > 0) {
                "epistola.support.hub.port must be a positive integer when epistola.support.hub.host is set"
            }
            return HubDiscovery.static(HubEndpoint(host = hub.host, port = hub.port, plaintext = hub.plaintext))
        }
        return HubDiscovery()
    }

    @Bean(destroyMethod = "close")
    fun epistolaHubClient(
        store: InstallationStore,
        props: SupportProperties,
        discovery: HubDiscovery,
    ): EpistolaHubClient = EpistolaHubClient(
        store = store,
        nodeId = props.hub.nodeId?.takeIf { it.isNotBlank() } ?: EpistolaHubClient.detectNodeId(),
        discoveryUrl = props.hub.discoveryUrl.ifBlank { DEFAULT_DISCOVERY_URL },
        discovery = discovery,
    )

    @Bean(destroyMethod = "close")
    fun hubRegistrationRetryLoop(
        client: EpistolaHubClient,
        metadata: InstallationMetadata,
    ): HubRegistrationRetryLoop = HubRegistrationRetryLoop(client, metadata)

    /**
     * Fire-and-forget kickoff on application ready — never blocks startup.
     * Terminal failures log and abort inside [HubRegistrationRetryLoop.start];
     * transient failures retry on a virtual thread until [HubRegistrationRetryLoop.close].
     *
     * Implemented as an [ApplicationListener] bean rather than an `@EventListener`
     * method so the listener resolves its dependency via the bean factory (which
     * keeps it out of `@Conditional`-on-property edge cases on method scanning).
     */
    @Bean
    fun startHubRegistration(
        loop: HubRegistrationRetryLoop,
    ): ApplicationListener<ApplicationReadyEvent> = ApplicationListener { loop.start() }
}
