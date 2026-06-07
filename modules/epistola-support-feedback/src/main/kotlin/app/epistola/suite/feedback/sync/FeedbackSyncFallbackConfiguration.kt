package app.epistola.suite.feedback.sync

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fallback configuration that registers a [NoOpFeedbackSyncAdapter] when no other
 * [FeedbackSyncPort] bean is present (e.g. when no sync provider is configured).
 *
 * Also registers [FeedbackSyncProperties] so generic sync settings are always available.
 */
@Configuration
@EnableConfigurationProperties(FeedbackSyncProperties::class)
class FeedbackSyncFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(FeedbackSyncPort::class)
    fun noOpFeedbackSyncAdapter(): FeedbackSyncPort = NoOpFeedbackSyncAdapter()
}
