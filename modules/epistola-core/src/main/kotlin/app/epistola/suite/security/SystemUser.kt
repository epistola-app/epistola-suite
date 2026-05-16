package app.epistola.suite.security

import app.epistola.suite.common.ids.UserKey

/**
 * The single, well-known **system** principal that owns every system-initiated
 * write: background document generation (the job poller), demo bootstrap, the
 * demo login resolver, and system-catalog install/upgrade.
 *
 * Audit columns (`created_by` / `updated_by`) are real foreign keys to
 * `users(id)`. Rather than provisioning this identity at runtime, it is seeded
 * as a `users` row by the `core_users` baseline migration
 * (`V20260515090100__core_users.sql`) — a database invariant, exactly like the
 * `installation` row in the app-metadata baseline. **Keep [ID] and the
 * identity fields in sync with that migration's seed.**
 *
 * The id is the all-zeros UUID: the conventional, obviously-synthetic system
 * identity (it was already the job poller's id). It is intentionally distinct
 * from the UUIDv7 ids minted for real users.
 */
object SystemUser {
    val ID: UserKey = UserKey.of("00000000-0000-0000-0000-000000000000")
    const val EXTERNAL_ID = "system"
    const val EMAIL = "system@epistola.app"
    const val DISPLAY_NAME = "System"
    const val PROVIDER = "LOCAL"
}
