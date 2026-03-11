package app.epistola.suite.feedback.sync.github

import app.epistola.suite.feedback.sync.FeedbackSyncPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

/**
 * Configuration for GitHub feedback sync.
 *
 * Always active — authentication is handled per-tenant via Personal Access Tokens
 * stored in [GitHubSyncSettings]. When no tenant has configured a PAT, no API calls are made.
 *
 * When the adapter bean exists, it takes precedence over the
 * [app.epistola.suite.feedback.sync.NoOpFeedbackSyncAdapter] (via `@ConditionalOnMissingBean`).
 */
@Configuration
class GitHubSyncConfiguration {

    @Bean
    fun gitHubRestClient(): RestClient = RestClient.builder()
        .baseUrl("https://api.github.com")
        .defaultHeader("Accept", "application/vnd.github+json")
        .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
        .build()

    @Bean
    @Primary
    fun gitHubFeedbackSyncAdapter(
        gitHubRestClient: RestClient,
        objectMapper: ObjectMapper,
    ): FeedbackSyncPort = GitHubIssueSyncAdapter(gitHubRestClient, objectMapper)
}
