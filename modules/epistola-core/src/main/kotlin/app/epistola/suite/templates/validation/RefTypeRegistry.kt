package app.epistola.suite.templates.validation

/**
 * Server-side mirror of the frontend `data-contract/ref-types.ts` registry.
 *
 * Single source of truth for known JSON Schema `$ref` URLs the editor and
 * server agree on. Adding a new ref type = one entry here + one entry on the
 * frontend.
 *
 * Two consumers today:
 *   - [JsonSchemaValidator] — preloads each entry's schema body into its
 *     `SchemaRegistry` so contract `$ref`s resolve at validation time.
 *   - server-rendered views (e.g. `DataContractTabHandler`) — map the canonical
 *     URL to a friendly type label.
 */
data class RefType(
    /** Stable logical id; matches the frontend's `RefType.id`. */
    val id: String,
    /** Canonical $ref URL (also the JSON Schema $id). */
    val url: String,
    /** Human label used by server-rendered views and any future API surface. */
    val label: String,
    /** Classpath path of the JSON Schema body, used by the validator's preload. */
    val schemaResourcePath: String,
)

object RefTypeRegistry {
    val ALL: List<RefType> = listOf(
        RefType(
            id = "richTextInline",
            url = "https://epistola.app/schemas/richtext-inline-v1.json",
            label = "Rich text (inline)",
            schemaResourcePath = "/schemas/richtext-inline-v1.json",
        ),
        RefType(
            id = "richTextBlock",
            url = "https://epistola.app/schemas/richtext-block-v1.json",
            label = "Rich text (block)",
            schemaResourcePath = "/schemas/richtext-block-v1.json",
        ),
    )

    /** Lookup by canonical URL. Returns null for unknown / null URLs. */
    fun findByUrl(url: String?): RefType? = url?.let { u -> ALL.firstOrNull { it.url == u } }

    /** Materialise the (iri, classpath body) pairs the validator needs at preload time. */
    fun preloadedSchemaResources(): List<Pair<String, String>> = ALL.map { it.url to it.schemaResourcePath }
}
