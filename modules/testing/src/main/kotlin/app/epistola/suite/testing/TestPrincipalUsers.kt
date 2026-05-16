package app.epistola.suite.testing

import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.users.AuthProvider
import app.epistola.suite.users.commands.EnsureUser

/**
 * Single test-infrastructure seam for the audit foreign keys.
 *
 * Domain audit columns (`created_by` / `updated_by`) are real foreign keys to
 * `users(id)`. In production every principal that performs a write is a real
 * `users` row (provisioned by the OAuth2 / local / demo authentication paths).
 * Tests authenticate as synthetic principals — a fixed harness principal plus
 * ad-hoc ones — that no production code provisions.
 *
 * Rather than maintaining a brittle fixed seed list or hand-writing
 * `INSERT INTO users` SQL, every place the test harness binds a principal
 * ([IntegrationTestBase.withMediator] / `runAs`, the fixture and scenario DSLs,
 * the HTTP test security filter) routes through here, which materialises the
 * `users` row by dispatching the **production** [EnsureUser] command — the same
 * idempotent provisioning the real local-auth path uses. Tests therefore
 * exercise the real provisioning code; there is no test-only SQL and no
 * production seam.
 */
object TestPrincipalUsers {
    /** Idempotently materialise a `users` row matching [principal] via [EnsureUser]. */
    fun ensure(mediator: Mediator, principal: EpistolaPrincipal): Unit = ensure(mediator, principal.userId, principal.externalId, principal.email, principal.displayName)

    /** Idempotently materialise a `users` row for [id] via [EnsureUser]. */
    fun ensure(
        mediator: Mediator,
        id: UserKey,
        externalId: String,
        email: String,
        displayName: String,
    ) {
        mediator.send(
            EnsureUser(
                id = id,
                externalId = externalId,
                email = email,
                displayName = displayName,
                provider = AuthProvider.LOCAL,
            ),
        )
    }

    /** [SecurityContext.runWithPrincipal], but the principal's `users` row is ensured first. */
    fun <T> runWithPrincipal(mediator: Mediator, principal: EpistolaPrincipal, block: () -> T): T {
        ensure(mediator, principal)
        return SecurityContext.runWithPrincipal(principal, block)
    }
}
