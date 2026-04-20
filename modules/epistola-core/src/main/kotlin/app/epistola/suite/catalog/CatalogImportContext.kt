package app.epistola.suite.catalog

/**
 * Scoped context that signals catalog import operations.
 *
 * When active, [requireCatalogEditable] skips the editability check,
 * allowing import commands to write to subscribed (read-only) catalogs.
 *
 * Uses [ScopedValue] (JDK 25) for automatic scope management and
 * virtual thread safety, consistent with [SecurityContext] and [MediatorContext].
 */
object CatalogImportContext {
    private val importing: ScopedValue<Boolean> = ScopedValue.newInstance()

    /** True if the current scope is executing a catalog import operation. */
    val isImporting: Boolean get() = importing.isBound && importing.get()

    /**
     * Runs the given block in import context, bypassing catalog editability checks.
     */
    fun <T> runAsImport(block: () -> T): T = ScopedValue.where(importing, true).call<T, RuntimeException>(block)
}
