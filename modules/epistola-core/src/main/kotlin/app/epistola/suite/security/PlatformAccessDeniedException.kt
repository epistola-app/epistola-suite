package app.epistola.suite.security

/**
 * Thrown when a user lacks a required platform-level role.
 *
 * This is a domain-level exception (no Spring Security dependency) that is
 * translated to HTTP 403 by the API exception handler.
 */
class PlatformAccessDeniedException(
    val userEmail: String,
    val requiredRole: PlatformRole,
) : RuntimeException("User $userEmail does not have platform role: $requiredRole")
