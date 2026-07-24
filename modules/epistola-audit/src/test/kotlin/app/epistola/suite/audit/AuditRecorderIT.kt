// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.audit

import app.epistola.suite.common.AuditDetailed
import app.epistola.suite.common.AuditedRead
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.commands.SetTenantDefaultLocale
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.users.commands.UpdateLastLogin
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import java.util.UUID

/**
 * Integration tests for [AuditRecorder] — the PII-free "who did what, when"
 * trail written from [app.epistola.suite.mediator.SpringMediator] for every
 * dispatched command (and every audited query).
 *
 * Verifies: successful commands are recorded WRITE with actor/tenant/entity/outcome;
 * failed commands are recorded as FAILURE with a non-PII error code and survive
 * the command's rollback (separate transaction); command field values (payloads)
 * never leak into any column; SystemInternal / NotAudited commands produce no
 * entry; and queries are recorded READ only when marked [AuditedRead].
 */
@Import(AuditRecorderIT.AuditTestQueryConfig::class)
class AuditRecorderIT : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `successful command is recorded with actor, tenant, entity and outcome`() {
        val tenant = createTenant("Audit Success")
        val tenantKey = tenant.id.value

        withMediator {
            // locale = null clears the override and succeeds without a code-list lookup.
            SetTenantDefaultLocale(tenantId = tenant.id, locale = null).execute()
        }

        val row = latestRow(tenantKey, "SetTenantDefaultLocale")
        assertThat(row).isNotNull
        assertThat(row!!.outcome).isEqualTo("SUCCESS")
        assertThat(row.operation).isEqualTo("WRITE")
        assertThat(row.actorUserId).isEqualTo(testUser.userId.value)
        assertThat(row.entityId).isEqualTo(tenantKey)
        assertThat(row.errorCode).isNull()
        assertThat(row.instanceId).isNotBlank()
    }

    @Test
    fun `a query marked AuditedRead is recorded as a READ with actor and tenant`() {
        val tenant = createTenant("Audit Read")
        val tenantKey = tenant.id.value

        withMediator {
            AuditedTestRead(tenant.id).query()
        }

        val row = latestRow(tenantKey, "AuditedTestRead")
        assertThat(row).isNotNull
        assertThat(row!!.operation).isEqualTo("READ")
        assertThat(row.outcome).isEqualTo("SUCCESS")
        assertThat(row.actorUserId).isEqualTo(testUser.userId.value)
    }

    @Test
    fun `an unmarked query is not recorded`() {
        val tenant = createTenant("Audit No Read")
        val tenantKey = tenant.id.value

        withMediator {
            PlainTestRead(tenant.id).query()
        }

        assertThat(latestRow(tenantKey, "PlainTestRead")).isNull()
    }

    @Test
    fun `AuditDetailed key-values are stored in the details column`() {
        val tenant = createTenant("Audit Details")
        val tenantKey = tenant.id.value

        withMediator {
            DetailedTestCommand(tenant.id).execute()
        }

        val row = latestRow(tenantKey, "DetailedTestCommand")
        assertThat(row).isNotNull
        assertThat(row!!.details).isNotNull()
        assertThat(row.details).contains("\"backupId\"", "0193-abc", "\"rows\"", "42")
    }

    @Test
    fun `tenant and entity are captured for permission-gated commands carrying a typed id`() {
        val tenant = createTenant("Audit Entity")
        val tenantKey = tenant.id.value
        // CreateEnvironment implements neither TenantScoped nor EntityIdentifiable — it
        // carries the tenant via RequiresPermission and the entity via a typed EnvironmentId.
        val envId = EnvironmentId(EnvironmentKey.of("audit-env"), TenantId(tenant.id))

        withMediator {
            CreateEnvironment(id = envId, name = "Audit Env").execute()
        }

        val row = latestRow(tenantKey, "CreateEnvironment")
        assertThat(row).isNotNull
        // tenant_key resolved from RequiresPermission (else this row would read as "system").
        assertThat(row!!.outcome).isEqualTo("SUCCESS")
        // entity derived generically from the typed id's path() ("environment:<tenant>/audit-env").
        assertThat(row.entityType).isEqualTo("environment")
        assertThat(row.entityId).contains("audit-env")
    }

    @Test
    fun `failed command is recorded as FAILURE with a non-PII error code and survives rollback`() {
        val tenant = createTenant("Audit Failure")
        val tenantKey = tenant.id.value

        runCatching {
            withMediator {
                // An unknown locale throws InvalidLocaleException; the command's own
                // transaction rolls back, but the audit FAILURE entry must persist.
                SetTenantDefaultLocale(tenantId = tenant.id, locale = "zz-not-a-locale").execute()
            }
        }

        val row = latestRow(tenantKey, "SetTenantDefaultLocale")
        assertThat(row).isNotNull
        assertThat(row!!.outcome).isEqualTo("FAILURE")
        // PII-free machine code (the exception class), never the message.
        assertThat(row.errorCode).isEqualTo("InvalidLocaleException")
    }

    @Test
    fun `command field values never leak into any audit column`() {
        val tenant = createTenant("Audit PII Guard")
        val tenantKey = tenant.id.value
        val marker = "PIIMARKER-${UUID.randomUUID()}"

        runCatching {
            withMediator {
                // The locale string is a command field (it would be in a payload dump).
                SetTenantDefaultLocale(tenantId = tenant.id, locale = marker).execute()
            }
        }

        // The FAILURE entry exists...
        assertThat(latestRow(tenantKey, "SetTenantDefaultLocale")?.outcome).isEqualTo("FAILURE")
        // ...but the command field value appears in NONE of the audit columns.
        val leaked = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(
                """
                SELECT count(*) FROM audit_log
                WHERE tenant_key = :tenantKey
                  AND (action ILIKE :m OR entity_type ILIKE :m OR entity_id ILIKE :m
                       OR error_code ILIKE :m OR instance_id ILIKE :m OR details::text ILIKE :m)
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("m", "%$marker%")
                .mapTo(Int::class.java)
                .one()
        }
        assertThat(leaked).isZero()
    }

    @Test
    fun `SystemInternal and NotAudited commands are not recorded`() {
        val before = countAction("UpdateLastLogin")

        withMediator {
            // UpdateLastLogin is SystemInternal + NotAudited.
            UpdateLastLogin(userId = testUser.userId).execute()
        }

        assertThat(countAction("UpdateLastLogin")).isEqualTo(before)
    }

    // -- helpers --------------------------------------------------------------

    private data class Row(
        val outcome: String,
        val operation: String,
        val actorUserId: UUID?,
        val entityType: String?,
        val entityId: String?,
        val errorCode: String?,
        val details: String?,
        val instanceId: String?,
    )

    private fun latestRow(tenantKey: String, action: String): Row? = jdbi.withHandle<Row?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT outcome, operation, actor_user_id, entity_type, entity_id, error_code, details::text AS details, instance_id
            FROM audit_log
            WHERE tenant_key = :tenantKey AND action = :action
            ORDER BY occurred_at DESC, id DESC
            LIMIT 1
            """,
        )
            .bind("tenantKey", tenantKey)
            .bind("action", action)
            .map { rs, _ ->
                Row(
                    outcome = rs.getString("outcome"),
                    operation = rs.getString("operation"),
                    actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                    entityType = rs.getString("entity_type"),
                    entityId = rs.getString("entity_id"),
                    errorCode = rs.getString("error_code"),
                    details = rs.getString("details"),
                    instanceId = rs.getString("instance_id"),
                )
            }
            .findOne()
            .orElse(null)
    }

    private fun countAction(action: String): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createQuery("SELECT count(*) FROM audit_log WHERE action = :action")
            .bind("action", action)
            .mapTo(Int::class.java)
            .one()
    }

    /** A tenant-scoped command carrying [AuditDetailed] key/values — they should land in `details`. */
    data class DetailedTestCommand(val tenantId: TenantKey) :
        Command<String>,
        RequiresPermission,
        AuditDetailed {
        override val permission get() = Permission.TENANT_SETTINGS
        override val tenantKey get() = tenantId
        override val auditDetails get() = mapOf("backupId" to "0193-abc", "rows" to "42")
    }

    class DetailedTestCommandHandler : CommandHandler<DetailedTestCommand, String> {
        override fun handle(command: DetailedTestCommand): String = "ok"
    }

    /** A tenant-scoped read marked [AuditedRead] — should produce a READ audit row. */
    data class AuditedTestRead(val tenantId: TenantKey) :
        Query<String>,
        RequiresPermission,
        AuditedRead {
        override val permission get() = Permission.DOCUMENT_VIEW
        override val tenantKey get() = tenantId
    }

    /** An identical read **without** the marker — should produce no audit row. */
    data class PlainTestRead(val tenantId: TenantKey) :
        Query<String>,
        RequiresPermission {
        override val permission get() = Permission.DOCUMENT_VIEW
        override val tenantKey get() = tenantId
    }

    class AuditedTestReadHandler : QueryHandler<AuditedTestRead, String> {
        override fun handle(query: AuditedTestRead): String = "ok"
    }

    class PlainTestReadHandler : QueryHandler<PlainTestRead, String> {
        override fun handle(query: PlainTestRead): String = "ok"
    }

    @TestConfiguration
    class AuditTestQueryConfig {
        @Bean
        fun auditedTestReadHandler() = AuditedTestReadHandler()

        @Bean
        fun plainTestReadHandler() = PlainTestReadHandler()

        @Bean
        fun detailedTestCommandHandler() = DetailedTestCommandHandler()
    }
}
