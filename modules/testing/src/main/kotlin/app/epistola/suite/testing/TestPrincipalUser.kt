package app.epistola.suite.testing

import app.epistola.suite.common.ids.UserKey

/**
 * The primary well-known principal used by every integration test
 * ([IntegrationTestBase], [TestFixtureFactory], [ScenarioFactory]).
 *
 * Audit columns (`created_by` / `updated_by`) are real foreign keys to
 * `users(id)`, so every principal that performs an authenticated write must
 * exist in the `users` table. The test harness materialises the row for
 * whatever principal it binds via [TestPrincipalUsers] — there is no fixed
 * seed list; ad-hoc principals are covered transparently.
 */
object TestPrincipalUser {
    val ID: UserKey = UserKey.of("00000000-0000-0000-0000-000000000099")

    // Globally-unique external_id: the `users` table has a UNIQUE
    // (external_id, provider) constraint. A generic value like "test-user"
    // risks colliding with users other tests/flows create, so this principal
    // owns an external_id nothing else uses.
    const val EXTERNAL_ID = "epistola-integration-test-principal"
    const val EMAIL = "integration-test-principal@epistola.test"
    const val DISPLAY_NAME = "Integration Test Principal"
    const val PROVIDER = "LOCAL"
}
