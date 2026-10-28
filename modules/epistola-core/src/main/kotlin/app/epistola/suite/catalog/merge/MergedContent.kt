// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.migration.CatalogMigrationContext
import app.epistola.catalog.migration.CatalogSchemaMigrator
import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.CatalogPresetDeclaration
import app.epistola.catalog.protocol.CatalogResource
import app.epistola.catalog.protocol.CatalogThemeRef
import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.catalog.validation.CatalogValidationCodes
import app.epistola.catalog.validation.ResourceValidationContext
import app.epistola.catalog.validation.ResourceValidator
import app.epistola.catalog.validation.TemplateValidationCodes
import app.epistola.catalog.validation.ValidationSeverity
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.dependencies.CatalogDependencyDeclaration
import app.epistola.suite.catalog.dependencies.CatalogDependencyStore
import app.epistola.suite.catalog.releases.CatalogPreset
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.sha256Hex
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream

/**
 * One entry of a merge in the portable catalog format (catalog wire v8, contract ADR 0005), as the
 * sides of an entry are shown ([GetDraftMergeSides]) and merged content is given
 * ([MergeReleaseIntoDraft.mergedContent]):
 *
 * - a resource: the `resource` object of its resource detail, with its `type` and `key`;
 * - `catalog/defaultTheme`: `{"defaultTheme": …}`, the manifest's `catalog.defaultTheme` or null;
 * - `catalog/presets`: `{"presets": […]}`, the manifest's `catalog.presets`;
 * - `catalogDependency/<catalogKey>`: the manifest's `catalogDependencies` entry, with `releaseId`
 *   naming a draft of that catalog (its `versionId`) when a draft's declaration names one.
 *
 * A null member and an absent one mean the same.
 */
@Component
class MergeEntryContent(private val objectMapper: ObjectMapper) {

    /** [value], an entry of [side], in the portable form; null when the side does not have it. */
    fun of(value: MergeValue?, side: MergeInput): Map<String, Any?>? = when (value) {
        null -> null

        is MergeValue.Resource -> tree(value.resource)

        is MergeValue.Setting -> when (val setting = value.setting) {
            is CatalogSetting.DefaultTheme -> mapOf("defaultTheme" to (side.content?.catalog?.defaultTheme ?: setting.theme?.let { CatalogThemeRef(it.themeKey.value, it.catalogKey.value) })?.let(::tree))

            is CatalogSetting.Presets -> mapOf("presets" to setting.presets.map { tree(CatalogPresetDeclaration(it.key, it.name, it.description)) })

            is CatalogSetting.Dependency -> setting.declaration.let { declaration ->
                linkedMapOf<String, Any?>(
                    "alias" to declaration.alias,
                    "registry" to declaration.registry,
                    "namespace" to declaration.namespace,
                    "catalogKey" to declaration.catalogKey,
                    "releaseVersion" to declaration.releaseVersion,
                    "releaseFingerprint" to declaration.releaseFingerprint,
                ) + (declaration.draftId?.let { mapOf("releaseId" to it) } ?: emptyMap())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tree(value: Any): Map<String, Any?> = objectMapper.convertValue(value, LinkedHashMap::class.java) as Map<String, Any?>
}

/**
 * Reads merged content given for one conflict of a merge ([MergeResolution.Content]) into what the
 * merge writes, validated as an import validates content:
 *
 * - a resource is bound as the protocol's resource of its type through the portable schema migrator
 *   (the import's own binding), must name the entry's `type` and `key`, and is validated by the
 *   portable [ResourceValidator]; the suite's own rules apply when it is written. A reference it
 *   holds that the merge's result does not have is left to [MergeReferenceCheck], which refuses the
 *   merge naming what to choose;
 * - a setting's value is parsed: a theme reference, a list of presets, a dependency declaration.
 *
 * @throws ValidationException naming the entry in the field, `mergedContent.<type/key>`, then the
 *   path within it.
 */
@Component
class MergedContentReader(
    private val objectMapper: ObjectMapper,
    private val fingerprints: CatalogFingerprintService,
    private val dependencyStore: CatalogDependencyStore,
    private val jdbi: Jdbi,
) {

    /** [content], given for the entry [sides] of [plan], as the value the merge writes. */
    fun read(catalog: CatalogId, plan: MergePlan, sides: MergeEntrySides, content: Map<String, Any?>): MergeValue {
        val entry = sides.contentKey
        return when (sides.type) {
            CatalogSetting.TYPE -> setting(catalog.key, entry, content)
            CatalogSetting.DEPENDENCY_TYPE -> dependency(catalog.tenantKey, entry, content)
            else -> resource(catalog.key, plan, sides, content)
        }
    }

    private fun resource(catalogKey: CatalogKey, plan: MergePlan, sides: MergeEntrySides, content: Map<String, Any?>): MergeValue.Resource {
        val entry = sides.contentKey
        val field = field(entry)
        val (type, key) = entry.substringBefore('/') to entry.substringAfter('/')
        if (content["type"] != type) throw ValidationException("$field/type", "must be the entry's type, $type")
        val givenKey = content["key"] ?: content["slug"]
        if (givenKey != key) throw ValidationException("$field/key", "must be the entry's key, $key")

        val draft = plan.inputs.ours.content
        val manifest = draft?.toManifest(ReleaseInfo(version = "0.0.0"))
            ?: error("A merge always reads the draft's content")
        val bytes = objectMapper.writeValueAsBytes(mapOf("schemaVersion" to CatalogWireSchema.CURRENT_VERSION, "resource" to content))
        val bound = CatalogSchemaMigrator.migrateResourceDetail(type, ByteArrayInputStream(bytes), CatalogMigrationContext(CatalogWireSchema.CURRENT_VERSION, manifest), field)
        val detail = bound.value ?: bound.findings.first().let { throw ValidationException(fieldOf(field, it.path), "The merged content of $entry is not a valid ${MergeReferences.word(type)}: ${it.message}") }

        // Its bytes, an image's or a font's faces, are those a side has: merged content names them by path.
        val binaries = listOfNotNull(sides.base, sides.theirs, sides.ours).filterIsInstance<MergeValue.Resource>().fold(emptyMap<String, ByteArray>()) { all, side -> all + side.binaries }
        val resources: Map<String, CatalogResource> = (plan.inputs.theirs.values + plan.inputs.ours.values)
            .mapNotNull { (k, v) -> (v as? MergeValue.Resource)?.let { k to it.resource } }
            .toMap()
        val report = ResourceValidator.validate(
            detail,
            ResourceValidationContext(
                catalogKey = catalogKey.value,
                resources = resources,
                paths = binaries.keys,
                catalogDependencies = manifest.catalogDependencies,
            ),
            field,
        )
        // A reference the result lacks is the merge's to refuse, with what to choose (MergeReferenceCheck).
        report.findings.firstOrNull { it.severity == ValidationSeverity.ERROR && it.code !in UNRESOLVED }?.let {
            throw ValidationException(fieldOf(field, it.path), "The merged content of $entry is not a valid ${MergeReferences.word(type)}: ${it.message}")
        }
        return MergeValue.Resource(detail.resource, fingerprintOf(content), binaries)
    }

    private fun setting(catalogKey: CatalogKey, entry: String, content: Map<String, Any?>): MergeValue.Setting {
        val field = field(entry)
        val setting = when (entry) {
            CatalogSetting.DefaultTheme(null).contentKey -> {
                val ref = content["defaultTheme"]?.let { convert(it, CatalogThemeRef::class.java, "$field/defaultTheme", "a theme reference: {\"key\": …, \"catalogKey\": …}") }
                CatalogSetting.DefaultTheme(
                    ref?.let {
                        val theme = keyed("$field/defaultTheme/key") { ThemeKey.of(it.key) }
                        ThemeByAlias(theme, it.catalogKey?.let { other -> keyed("$field/defaultTheme/catalogKey") { CatalogKey.of(other) } } ?: catalogKey)
                    },
                )
            }

            CatalogSetting.Presets(emptyList()).contentKey -> {
                val given = content["presets"] ?: emptyList<Any>()
                val presets = convert(given, Array<CatalogPresetDeclaration>::class.java, "$field/presets", "a list of presets, each {\"key\": …, \"name\": …}")
                CatalogSetting.Presets(
                    presets.mapIndexed { index, preset ->
                        try {
                            CatalogPreset(preset.key, preset.name, preset.description)
                        } catch (e: ValidationException) {
                            throw ValidationException("$field/presets[$index]", e.message, e.code)
                        }
                    },
                )
            }

            else -> throw ValidationException(field, "$entry is not a setting a merge has")
        }
        return MergeValue.Setting(setting, fingerprints.settingFingerprint(setting.canonical))
    }

    private fun dependency(tenantKey: TenantKey, entry: String, content: Map<String, Any?>): MergeValue.Setting {
        val field = field(entry)
        val key = entry.substringAfter('/')
        fun text(member: String, required: Boolean): String? {
            val value = content[member]
            if (value == null) {
                if (required) throw ValidationException("$field/$member", "is required")
                return null
            }
            return (value as? String)?.takeIf { it.isNotBlank() } ?: throw ValidationException("$field/$member", "must be a nonblank string")
        }
        val catalog = text("catalogKey", required = true)
        if (catalog != key) throw ValidationException("$field/catalogKey", "must be the entry's catalog, $key")
        // This installation declares every catalog under its key (DependencyAliases).
        text("alias", required = false)?.let { if (it != key) throw ValidationException("$field/alias", "must be the catalog's key, $key: this installation names every catalog a catalog uses by its key") }
        val registry = text("registry", required = true)!!
        val namespace = text("namespace", required = true)!!
        val version = text("releaseVersion", required = false)
        val fingerprint = text("releaseFingerprint", required = false)
        val draftId = text("releaseId", required = false)
        if (draftId != null && version != null) throw ValidationException("$field/releaseId", "names a draft, which pins no release: leave out releaseVersion and releaseFingerprint")
        if (version != null && fingerprint == null) throw ValidationException("$field/releaseFingerprint", "is required with releaseVersion")
        if (fingerprint != null && version == null) throw ValidationException("$field/releaseVersion", "is required with releaseFingerprint")
        if (fingerprint != null && !app.epistola.catalog.protocol.CatalogDependency.isValidReleaseFingerprint(fingerprint)) {
            throw ValidationException("$field/releaseFingerprint", "must be a release fingerprint, v6:<hex> (or of an earlier algorithm)")
        }
        var declaration = CatalogDependencyDeclaration(registry, namespace, key, version, fingerprint, draftId)
        if (draftId != null) {
            val named = jdbi.withHandle<Pair<java.util.UUID, String>?, Exception> { dependencyStore.draftOf(it, tenantKey, declaration, draftId) }
                ?: throw ValidationException("$field/releaseId", "names no draft of '$key'")
            declaration = declaration.copy(draftName = named.second)
        }
        val setting = CatalogSetting.Dependency(declaration)
        return MergeValue.Setting(setting, fingerprints.settingFingerprint(setting.canonical))
    }

    private fun <T : Any> convert(value: Any, type: Class<T>, field: String, expected: String): T = try {
        objectMapper.convertValue(value, type)
    } catch (e: JacksonException) {
        throw ValidationException(field, "must be $expected")
    } catch (e: IllegalArgumentException) {
        throw ValidationException(field, "must be $expected")
    }

    private fun <T> keyed(field: String, make: () -> T): T = try {
        make()
    } catch (e: IllegalArgumentException) {
        throw ValidationException(field, e.message ?: "is not a valid key")
    }

    // Merged content is never compared by its fingerprint: a merge writes it, and the draft as written
    // is what later merges compare. This one only tells two contents apart.
    private fun fingerprintOf(content: Map<String, Any?>): String = sha256Hex(objectMapper.writeValueAsBytes(content))

    private companion object {
        /** What the validator finds when a reference does not resolve, which [MergeReferenceCheck] refuses with what to choose. */
        val UNRESOLVED = setOf(
            CatalogValidationCodes.RESOURCE_REFERENCE_MISSING,
            // An alias the draft does not declare: whether the result declares it is the reference check's to say.
            CatalogValidationCodes.DEPENDENCY_CATALOG_UNDECLARED,
            TemplateValidationCodes.TEMPLATE_THEME_NOT_FOUND,
            TemplateValidationCodes.STENCIL_REFERENCE_NOT_FOUND,
        )

        fun field(entry: String) = "mergedContent.$entry"

        /**
         * A finding's path (`mergedContent.template/invoice.resource.variants[0].key`) as a field of
         * the merge: the entry, then the path within the content (`mergedContent.template/invoice/variants[0]/key`).
         */
        fun fieldOf(field: String, path: String): String {
            val within = path.removePrefix(field).removePrefix(".resource").removePrefix(".").replace('.', '/')
            return if (within.isEmpty()) field else "$field/$within"
        }
    }
}
