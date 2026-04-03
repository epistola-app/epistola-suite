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
    /** Mock value used by the editor for expression preview. */
    val mockValue: Any? = null,
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
                mockValue = 1,
            ),
        )
        register(
            SystemParameterDescriptor(
                path = "page.total",
                description = "Total number of pages. Available in page headers/footers only.",
                type = "integer",
                scope = SystemParamScope.PAGE_SCOPED,
                mockValue = 1,
            ),
        )
        register(
            SystemParameterDescriptor(
                path = "page.pageOfTotal",
                description = "Page number and total in format '1 of 5'. Available in page headers/footers only.",
                type = "string",
                scope = SystemParamScope.PAGE_SCOPED,
                mockValue = "1 of 1",
            ),
        )
    }

    fun register(descriptor: SystemParameterDescriptor) {
        descriptors.add(descriptor)
    }

    fun all(): List<SystemParameterDescriptor> = descriptors.toList()

    /** Build a nested map from dot-path keys and their values. */
    fun buildNestedMap(values: Map<String, Any?>): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((path, value) in values) {
            val parts = path.split(".")
            var current = result
            for (i in 0 until parts.size - 1) {
                @Suppress("UNCHECKED_CAST")
                current = current.getOrPut(parts[i]) { mutableMapOf<String, Any?>() } as MutableMap<String, Any?>
            }
            current[parts.last()] = value
        }
        return result
    }

    /** Build page-scoped system parameters (for headers/footers). */
    fun buildPageParams(pageNumber: Int, totalPages: Int): Map<String, Any?> = buildNestedMap(
        mapOf(
            "page.number" to pageNumber,
            "page.total" to totalPages,
            "page.pageOfTotal" to "$pageNumber of $totalPages",
        ),
    )

    /** Build global system parameters (currently empty, but extensible). */
    fun buildGlobalParams(): Map<String, Any?> = emptyMap()
}
