package app.epistola.suite.catalog

/**
 * Thrown when a tenant-scoped catalog lookup finds no such catalog.
 *
 * Maps to **404 Not Found** on the REST surface; on MCP the message is
 * surfaced as the tool error. Replaces a bare `IllegalArgumentException`
 * (which the API advice mapped to a misleading 400).
 */
class CatalogNotFoundException(
    val catalogKey: CatalogKey,
) : RuntimeException("Catalog not found: ${catalogKey.value}")

/**
 * Thrown when an upgrade/upgrade-preview is requested for a catalog that
 * cannot be upgraded — it is not a subscribed catalog (no source URL), or it
 * is subscribed but has no per-resource baseline captured yet.
 *
 * Maps to **409 Conflict** on the REST surface (wrong catalog state for the
 * operation, not a client input error); on MCP the [message] is surfaced as
 * the tool error. Replaces a bare `IllegalStateException` (which fell through
 * to the generic 500 handler).
 */
class CatalogNotUpgradeableException(
    val catalogKey: CatalogKey,
    reason: String,
) : RuntimeException("Catalog '${catalogKey.value}' cannot be upgraded: $reason")
