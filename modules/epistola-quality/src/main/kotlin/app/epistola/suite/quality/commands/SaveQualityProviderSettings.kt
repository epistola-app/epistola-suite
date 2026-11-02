// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySourceRegistry
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** The switchable providers that are on for the tenant after saving. */
data class SavedQualityProviderSettings(
    val enabled: Set<QualitySourceId>,
)

/**
 * Sets which quality providers a tenant uses (ADR 0047): every switchable provider this installation
 * configured is on when it is in [enabled] and off otherwise. A tenant administrator's decision,
 * because switching one on sends the tenant's template text to it.
 *
 * A provider the installation did not configure, or that is not switchable, cannot be named: its
 * switch would record a choice that changes nothing.
 */
data class SaveQualityProviderSettings(
    override val tenantKey: TenantKey,
    val enabled: Set<QualitySourceId>,
) : Command<SavedQualityProviderSettings>,
    RequiresPermission {
    override val permission: Permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class SaveQualityProviderSettingsHandler(
    private val jdbi: Jdbi,
    private val registry: QualitySourceRegistry,
) : CommandHandler<SaveQualityProviderSettings, SavedQualityProviderSettings> {
    override fun handle(command: SaveQualityProviderSettings): SavedQualityProviderSettings {
        val switchable = registry.textCheckers().filter { it.switchable && it.configured }.map { it.checkerId }.toSet()
        val unknown = command.enabled - switchable
        if (unknown.isNotEmpty()) {
            throw ValidationException("enabled", "Not a provider this installation lets a tenant switch: ${unknown.joinToString()}")
        }
        val now = EpistolaClock.offsetDateTime()
        jdbi.useHandle<Exception> { handle ->
            val batch = handle.prepareBatch(
                """
                INSERT INTO quality_provider_settings (tenant_key, provider_id, enabled, updated_at, updated_by)
                VALUES (:tenantKey, :providerId, :enabled, :now, :actor)
                ON CONFLICT (tenant_key, provider_id) DO UPDATE
                SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
                WHERE quality_provider_settings.enabled IS DISTINCT FROM EXCLUDED.enabled
                """,
            )
            switchable.forEach { id ->
                batch.bind("tenantKey", command.tenantKey)
                    .bind("providerId", id.value)
                    .bind("enabled", id in command.enabled)
                    .bind("now", now)
                    .bind("actor", currentUserIdOrNull()?.value)
                    .add()
            }
            if (switchable.isNotEmpty()) batch.execute()
        }
        return SavedQualityProviderSettings(command.enabled)
    }
}
