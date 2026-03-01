package app.epistola.generation

/**
 * Scope at which a system parameter is available.
 */
enum class SystemParamScope {
    /** Available in all contexts (body, headers, footers). */
    GLOBAL,

    /** Available only in page-scoped contexts (headers, footers). */
    PAGE_SCOPED,
}

/**
 * Describes a single system parameter that the rendering engine provides.
 *
 * @param path Dot-notation path under the `sys` namespace (e.g., "page.number")
 * @param description Human-readable description for editor UI
 * @param type The value type (e.g., "integer", "string")
 * @param scope Where this parameter is available
 */
data class SystemParameterDescriptor(
    val path: String,
    val description: String,
    val type: String,
    val scope: SystemParamScope,
) {
    /** Full dotted path including the `sys.` prefix (e.g., "sys.page.number"). */
    val fullPath: String get() = "sys.$path"
}

/**
 * Registry of system parameters available to templates at render time.
 *
 * System parameters are runtime values injected by the rendering engine
 * (e.g., page numbers) that are independent of the template's data model.
 */
object SystemParameterRegistry {
    private val descriptors = mutableListOf<SystemParameterDescriptor>()

    init {
        register(
            SystemParameterDescriptor(
                path = "page.number",
                description = "Current page number. Available in page headers/footers only.",
                type = "integer",
                scope = SystemParamScope.PAGE_SCOPED,
            ),
        )
    }

    fun register(descriptor: SystemParameterDescriptor) {
        descriptors.add(descriptor)
    }

    fun all(): List<SystemParameterDescriptor> = descriptors.toList()
}
