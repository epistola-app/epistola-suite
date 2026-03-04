package app.epistola.suite.feedback.sync

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fallback configuration that registers a [NoOpIssueSyncAdapter] when no other
 * [IssueSyncPort] bean is present (e.g. when GitHub integration is not configured).
 */
@Configuration
class FeedbackSyncFallbackConfiguration {

    @Bean
    @ConditionalOnMissingBean(IssueSyncPort::class)
    fun noOpIssueSyncAdapter(): IssueSyncPort = NoOpIssueSyncAdapter()
}
