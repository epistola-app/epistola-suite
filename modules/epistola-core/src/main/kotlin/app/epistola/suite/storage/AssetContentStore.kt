package app.epistola.suite.storage

/**
 * Storage for **permanent asset** blobs (logos, images, font files) — issue #738.
 *
 * Content-addressable and deduplicated: blobs are keyed by `(scope, sha256)` so
 * identical bytes are stored once. Always PostgreSQL, regardless of the configured
 * `epistola.storage.backend` — assets are small, low-churn, keep-forever reference
 * data. The `assets.content_hash` column points into this store.
 *
 * Deduplication scope is a privacy boundary derived from the asset's `sensitive` flag:
 * non-sensitive assets use a single `"global"` scope (dedup installation-wide), while
 * sensitive assets scope to their tenant key (isolated — no cross-tenant existence
 * side-channel, clean erasure). Callers derive the scope with
 * [app.epistola.suite.assets.assetContentScope].
 *
 * There is no public delete: a blob may back many assets, so reclamation is
 * mark-and-sweep by the content reaper once no `assets` row references its hash.
 */
interface AssetContentStore {

    /**
     * Store [content] under `(scope, sha256)` if absent; a no-op if the blob already
     * exists (deduplication). Idempotent — safe to call for every asset write.
     */
    fun putIfAbsent(scope: String, sha256: String, content: ByteArray, contentType: String, sizeBytes: Long)

    /** Retrieve content by `(scope, sha256)`, or null if not found. */
    fun get(scope: String, sha256: String): StoredContent?

    /** Check whether content exists at `(scope, sha256)`. */
    fun exists(scope: String, sha256: String): Boolean
}
