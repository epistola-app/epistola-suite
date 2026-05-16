package app.epistola.suite.testing

import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked

/**
 * Single test-infrastructure seam for the audit foreign keys.
 *
 * Domain audit columns (`created_by` / `updated_by`) are real foreign keys to
 * `users(id)` with `ON DELETE SET NULL`. In production every principal that
 * performs a write is a real `users` row (provisioned by the OAuth2 / local /
 * demo authentication paths). Tests authenticate as synthetic principals —
 * fixed well-known ids, deterministically-derived ids and ad-hoc ids — that no
 * production code provisions.
 *
 * Rather than maintaining a brittle fixed seed list, every place the test
 * harness binds a principal ([IntegrationTestBase.withMediator] / `runAs`, the
 * fixture and scenario DSLs, the HTTP test security filter) routes through
 * [runWithPrincipal] here, which idempotently materialises a `users` row for
 * whatever principal is being bound before delegating to the production
 * [SecurityContext.runWithPrincipal]. This transparently covers every test
 * principal with no production seam.
 */
object TestPrincipalUsers {
    /** Idempotently inserts a `users` row matching [principal] (no-op if it already exists). */
    fun ensure(jdbi: Jdbi, principal: EpistolaPrincipal) {
        jdbi.withHandleUnchecked { handle ->
            handle.createUpdate(
                """
                INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
                VALUES (:id, :externalId, :email, :displayName, '${TestPrincipalUser.PROVIDER}', true, NOW())
                -- Idempotent on the principal id. A (external_id, provider)
                -- clash is intentionally NOT swallowed: it means two distinct
                -- synthetic principals reuse the same external_id, which is a
                -- test-authoring error to fix (give them distinct external_ids).
                ON CONFLICT (id) DO NOTHING
                """,
            )
                .bind("id", principal.userId.value)
                .bind("externalId", principal.externalId)
                .bind("email", principal.email)
                .bind("displayName", principal.displayName)
                .execute()
        }
    }

    /** [SecurityContext.runWithPrincipal], but the principal's `users` row is ensured first. */
    fun <T> runWithPrincipal(jdbi: Jdbi, principal: EpistolaPrincipal, block: () -> T): T {
        ensure(jdbi, principal)
        return SecurityContext.runWithPrincipal(principal, block)
    }
}
