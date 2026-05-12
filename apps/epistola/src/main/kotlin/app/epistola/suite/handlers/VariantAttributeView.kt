package app.epistola.suite.handlers

import app.epistola.suite.attributes.model.VariantAttributeDefinition

/**
 * UI-side view of one tenant attribute definition, decorated with the values
 * already used by variants of the current template + the dropdown options
 * derived from the attribute's constraint (inline allowed values, or code
 * list entries).
 *
 * `qualifiedKey` is the form variant attribute maps now use after the
 * catalog-qualified-references work — `"<catalogKey>.<slug>"`. It's also the
 * key we hand to form inputs (`name="attr_system.locale"`), the filter-bar
 * data attribute, and the variant-card data attribute, so client-side JS can
 * round-trip without re-resolving the catalog.
 *
 * `bareSlug` is the legacy form (`name="attr_locale"`). Until existing variant
 * attribute maps are migrated, we keep both reachable from the same
 * descriptor so a card showing `language = en` resolves to the same row as
 * `system.language = en`.
 */
data class AttributeDescriptor(
    val qualifiedKey: String,
    val catalogKey: String,
    val bareSlug: String,
    val displayName: String,
    val options: List<AttributeOption>,
)

/**
 * Build a descriptor list from raw definitions. Indexable by either the
 * qualified key or the bare slug — see [resolveAttributeKey].
 */
fun buildAttributeDescriptors(definitions: List<VariantAttributeDefinition>): List<AttributeDescriptor> = definitions.map { def ->
    AttributeDescriptor(
        qualifiedKey = "${def.catalogKey.value}.${def.id.value}",
        catalogKey = def.catalogKey.value,
        bareSlug = def.id.value,
        displayName = def.displayName,
        options = buildAttributeOptions(listOf(def))[def.id.value].orEmpty(),
    )
}

/**
 * Resolve a key as it appears in `variant.attributes` (either qualified or
 * bare) to one of the tenant's attribute descriptors. Bare slugs that exist
 * in multiple catalogs hit the silent-collision case (we pick the first
 * match) — the same situation the variant validator's tenant-wide lookup
 * has always had.
 */
fun resolveAttributeKey(rawKey: String, descriptors: List<AttributeDescriptor>): AttributeDescriptor? = if ('.' in rawKey) {
    descriptors.firstOrNull { it.qualifiedKey == rawKey }
} else {
    descriptors.firstOrNull { it.bareSlug == rawKey }
}

/**
 * For each variant, return its attribute entries enriched with the matching
 * descriptor (or null if the key is orphaned — definition deleted, value
 * still on the variant). Preserves insertion order so the card layout stays
 * stable across re-renders.
 */
data class ResolvedAttributeEntry(
    val rawKey: String,
    val descriptor: AttributeDescriptor?,
    val value: String,
) {
    /** Qualified key for use as a data attribute / filter key. */
    val key: String get() = descriptor?.qualifiedKey ?: rawKey

    /** Human-friendly catalog label. Falls back to the raw key's prefix. */
    val catalogLabel: String get() = descriptor?.catalogKey ?: rawKey.substringBefore('.', "")

    /** Slug only — what UIs traditionally render. */
    val slug: String get() = descriptor?.bareSlug ?: rawKey.substringAfter('.', rawKey)

    /** Display label (the attribute's `displayName`, or the slug as fallback). */
    val label: String get() = descriptor?.displayName ?: slug
}

fun resolveVariantAttributes(
    attributes: Map<String, String>,
    descriptors: List<AttributeDescriptor>,
): List<ResolvedAttributeEntry> = attributes.map { (key, value) ->
    ResolvedAttributeEntry(rawKey = key, descriptor = resolveAttributeKey(key, descriptors), value = value)
}

/**
 * Variant summary decorated with its resolved attribute entries — keeps the
 * descriptor + value pairing co-located with the variant so Thymeleaf can
 * iterate it via simple bean access (`variant.entries`) instead of looking
 * the map up by a key whose type (the inline-class `VariantKey`) may not
 * round-trip cleanly through Thymeleaf's expression parser.
 */
data class VariantWithEntries(
    val summary: app.epistola.suite.templates.model.VariantSummary,
    val entries: List<ResolvedAttributeEntry>,
) {
    val id get() = summary.id
    val title get() = summary.title
    val isDefault get() = summary.isDefault
    val hasDraft get() = summary.hasDraft
    val publishedVersions get() = summary.publishedVersions
    val attributes get() = summary.attributes
}

fun decorateVariants(
    variants: List<app.epistola.suite.templates.model.VariantSummary>,
    descriptors: List<AttributeDescriptor>,
): List<VariantWithEntries> = variants.map { v ->
    VariantWithEntries(summary = v, entries = resolveVariantAttributes(v.attributes, descriptors))
}

/**
 * Restrict descriptors to attributes actually used by at least one variant
 * — what the filter bar should show. Filtering by a key no variant
 * uses produces an always-empty result; offering it is pure clutter.
 */
fun filterToUsedDescriptors(
    descriptors: List<AttributeDescriptor>,
    decorated: List<VariantWithEntries>,
): List<AttributeDescriptor> {
    val usedQualified = decorated
        .flatMap { v -> v.entries.mapNotNull { it.descriptor?.qualifiedKey } }
        .toSet()
    return descriptors.filter { it.qualifiedKey in usedQualified }
}
