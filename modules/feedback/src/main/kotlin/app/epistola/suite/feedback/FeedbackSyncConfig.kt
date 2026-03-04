package app.epistola.suite.feedback

import app.epistola.suite.common.ids.TenantKey
import java.time.Instant

data class FeedbackSyncConfig(
    val tenantKey: TenantKey,
    val enabled: Boolean,
    val providerType: SyncProviderType,
    val settings: String,
    val lastPolledAt: Instant?,
)

enum class SyncProviderType {
    GITHUB,
    JIRA,
}
