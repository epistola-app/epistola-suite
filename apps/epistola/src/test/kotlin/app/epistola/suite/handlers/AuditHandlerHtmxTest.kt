// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Server-contract cover for the tenant Audit viewer: the full page renders the
 * tenant's rows plus system rows, the actor is resolved to a display name (or
 * "(deleted user)" when the user row is gone), the outcome/action filters scope
 * rows, and the keyset paging endpoints behave.
 */
class AuditHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    private companion object {
        // datetime-local filter bounds (UTC, since no tz param) bracketing the 08:00Z seed window.
        const val WINDOW = "from=2026-06-10T07:00&to=2026-06-10T09:00"

        // Keyset cursors just outside the seed window (UTC 'Z' literals avoid '+' in the query string).
        const val WINDOW_END_CURSOR = "2026-06-10T08:01:00Z"
        const val WINDOW_START_CURSOR = "2026-06-10T07:59:00Z"
    }

    @Test
    fun `audit page renders tenant rows, system rows, and resolves the actor`() {
        val tenantKey = seedAudit()

        // Scope to the seed window: the viewer also shows system (no-tenant) rows,
        // so an unfiltered page would mix in audit rows produced by every other
        // test. The window makes assertions deterministic.
        val response = get("/tenants/$tenantKey/audit?$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Audit")
        assertThat(response.body).contains("CreateTheme")
        // System (no-tenant) row is in scope.
        assertThat(response.body).contains("UpgradeCatalog")
        // Actor resolved via the LEFT JOIN to users (the test principal's row exists)...
        assertThat(response.body).contains("Integration Test Principal")
        // ...and a row whose actor no longer exists falls back, never crashes.
        assertThat(response.body).contains("(deleted user)")
    }

    @Test
    fun `HTMX search filters by outcome`() {
        val tenantKey = seedAudit()

        val response = getHtmx("/tenants/$tenantKey/audit/search?outcome=FAILURE&$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("SetTenantDefaultLocale")
        assertThat(response.body).contains("InvalidLocaleException")
        // The SUCCESS rows must be filtered out.
        assertThat(response.body).doesNotContain("CreateTheme")
    }

    @Test
    fun `HTMX search filters by action`() {
        val tenantKey = seedAudit()

        val response = getHtmx("/tenants/$tenantKey/audit/search?action=CreateTheme&$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("CreateTheme")
        assertThat(response.body).doesNotContain("DeleteTheme")
    }

    @Test
    fun `HTMX search filters by operation READ`() {
        val tenantKey = seedAudit()

        val response = getHtmx("/tenants/$tenantKey/audit/search?operation=READ&$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // The audited data-access read is shown with its "read" tag...
        assertThat(response.body).contains("GetDocument")
        assertThat(response.body).contains("audit-read-tag")
        // ...and the WRITE rows are filtered out.
        assertThat(response.body).doesNotContain("CreateTheme")
    }

    @Test
    fun `HTMX load-older returns older rows plus a refreshed sentinel`() {
        val tenantKey = seedAudit()
        // A cursor just after the seed window makes every seeded row qualify as "older".
        val response = getHtmx("/tenants/$tenantKey/audit/older?ts=${WINDOW_END_CURSOR}&id=ffffffff-ffff-ffff-ffff-ffffffffffff&$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("CreateTheme")
    }

    @Test
    fun `HTMX load-newer returns the newer sentinel`() {
        val tenantKey = seedAudit()
        // A cursor just before the seed window makes every seeded row qualify as "newer".
        val response = getHtmx("/tenants/$tenantKey/audit/newer?ts=${WINDOW_START_CURSOR}&id=00000000-0000-0000-0000-000000000000&$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("audit-load-newer")
        assertThat(response.body).contains("CreateTheme")
    }

    @Test
    fun `a malformed pagination cursor is a 400, not a 500`() {
        val tenantKey = seedAudit()
        val response = getHtmx("/tenants/$tenantKey/audit/older?ts=garbage&id=not-a-uuid")
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a typed entity renders as a readable link to its resource`() {
        val tenantKey = seedAudit()

        val response = get("/tenants/$tenantKey/audit?$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // The variant row shows "template invoice › variant nl" linking to the template editor.
        assertThat(response.body).contains("/tenants/demo/templates/default/invoice")
        assertThat(response.body).contains("variant nl")
    }

    @Test
    fun `author-supplied details render as key value chips`() {
        val tenantKey = seedAudit()

        val response = get("/tenants/$tenantKey/audit?$WINDOW")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        // The RestoreTenantBackup row carries {"backupId": "..."} as a chip.
        assertThat(response.body).contains("backupId: 0193-restore-me")
    }

    @Test
    fun `an empty result renders the empty state`() {
        val tenantKey = seedAudit()
        val response = getHtmx("/tenants/$tenantKey/audit/search?action=NoSuchActionEver&$WINDOW")
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("No audit entries")
        assertThat(response.body).doesNotContain("CreateTheme")
    }

    /**
     * Seeds `audit_log` directly: it is a runtime/audit table with no command of
     * its own (rows are a side effect of dispatching other commands), so a raw
     * insert is the justified exception to the seed-through-commands rule — and
     * it lets us plant deterministic actors, outcomes, and timestamps inside a
     * fixed window that the tests filter to (immune to other tests' system rows).
     */
    private fun seedAudit(): String {
        val tenant: Tenant = createTenant("Audit Viewer") // also ensures the test principal's users row
        val tenantKey = tenant.id.value
        val base = OffsetDateTime.parse("2026-06-10T08:00:00Z") // inside the June partition, inside WINDOW

        insert(tenantKey, testUser.userId.value, "CreateTheme", "WRITE", "SUCCESS", null, base.plusSeconds(40))
        insert(tenantKey, UUID.randomUUID(), "DeleteTheme", "WRITE", "SUCCESS", null, base.plusSeconds(30)) // unknown actor → "(deleted user)"
        insert(tenantKey, testUser.userId.value, "SetTenantDefaultLocale", "WRITE", "FAILURE", "InvalidLocaleException", base.plusSeconds(20))
        insert(tenantKey, testUser.userId.value, "GetDocument", "READ", "SUCCESS", null, base.plusSeconds(15)) // audited data-access read
        // A typed entity (variant) — the viewer should render a readable label + a link to the template.
        insert(tenantKey, testUser.userId.value, "UpdateDraft", "WRITE", "SUCCESS", null, base.plusSeconds(12), entityType = "variant", entityId = "demo/default/invoice/nl")
        // Author-supplied key/values render as chips.
        insert(tenantKey, testUser.userId.value, "RestoreTenantBackup", "WRITE", "SUCCESS", null, base.plusSeconds(11), details = """{"backupId":"0193-restore-me"}""")
        insert(null, null, "UpgradeCatalog", "WRITE", "SUCCESS", null, base.plusSeconds(10)) // system / no-tenant row
        return tenantKey
    }

    private fun insert(
        tenantKey: String?,
        actorUserId: UUID?,
        action: String,
        operation: String,
        outcome: String,
        errorCode: String?,
        occurredAt: OffsetDateTime,
        entityType: String? = null,
        entityId: String? = null,
        details: String? = null,
    ) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO audit_log
                    (id, occurred_at, tenant_key, actor_user_id, action, operation, entity_type, entity_id, outcome, error_code, details, instance_id)
                VALUES
                    (:id, :occurredAt, :tenantKey, :actorUserId, :action, :operation, :entityType, :entityId, :outcome, :errorCode, :details::jsonb, 'test-instance')
                """,
            )
                .bind("id", UUIDv7.generate())
                .bind("occurredAt", occurredAt)
                .bind("tenantKey", tenantKey)
                .bind("actorUserId", actorUserId)
                .bind("action", action)
                .bind("operation", operation)
                .bind("entityType", entityType)
                .bind("entityId", entityId)
                .bind("outcome", outcome)
                .bind("errorCode", errorCode)
                .bind("details", details)
                .execute()
        }
    }

    private fun get(url: String): ResponseEntity<String> = restTemplate.getForEntity(url, String::class.java)

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }
}
