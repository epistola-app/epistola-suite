package app.epistola.suite.feedback.sync.github

import app.epistola.suite.feedback.sync.FeedbackSyncPort
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

/**
 * Configuration for GitHub App integration.
 *
 * Only active when `epistola.github.app-id` is set. When inactive, the
 * [app.epistola.suite.feedback.sync.NoOpFeedbackSyncAdapter] is used instead
 * (via `@ConditionalOnMissingBean`).
 */
@Configuration
@ConditionalOnProperty("epistola.github.app-id")
@EnableConfigurationProperties(GitHubAppProperties::class)
class GitHubSyncConfiguration {

    @Bean
    fun gitHubRestClient(): RestClient = RestClient.builder()
        .baseUrl("https://api.github.com")
        .defaultHeader("Accept", "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .build()

    @Bean
    fun gitHubAppAuthService(
        properties: GitHubAppProperties,
        gitHubRestClient: RestClient,
    ): GitHubAppAuthService = GitHubAppAuthService(properties, gitHubRestClient)

    @Bean
    fun gitHubFeedbackSyncAdapter(
        authService: GitHubAppAuthService,
        gitHubRestClient: RestClient,
        objectMapper: ObjectMapper,
    ): FeedbackSyncPort = GitHubIssueSyncAdapter(authService, gitHubRestClient, objectMapper)
}
