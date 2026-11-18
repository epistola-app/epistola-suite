// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.kernel.xmp.XMPConst
import com.itextpdf.kernel.xmp.XMPMeta
import com.itextpdf.kernel.xmp.XMPMetaFactory
import com.itextpdf.kernel.xmp.XMPPathFactory
import com.itextpdf.kernel.xmp.options.PropertyOptions

/**
 * Writes a [GenerationContext] to a PDF's XMP metadata, under its own namespace.
 *
 * PDF/A allows a property outside the predefined schemas only when the XMP describes it in an
 * extension schema (ISO 19005-2, 6.6.2.3). [write] sets the values; [describe] adds the description
 * when the document is closed, after iText has added its own (PDF/UA's): iText merges its schemas
 * into ours not at all (`XMPUtilsImpl.appendSubtree` compares the two arrays' options by
 * reference), so whichever writes the bag first is the only one in it. The description is written
 * without PDF/A too: one shape for every document. The properties are a published format,
 * which integrators read: a change to one needs a new namespace version, not an edit.
 */
object GenerationContextXmp {
    const val NAMESPACE = "https://epistola.app/ns/generation/1.0/"
    const val PREFIX = "epistola"

    /** A property: its name in the namespace, its XMP value type, and what it holds. */
    private data class Property(val name: String, val valueType: String, val description: String)

    private val PROPERTIES = listOf(
        Property("tenant", "Text", "Key of the tenant that generated the document"),
        Property("registry", "Text", "Registry of the catalog holding the template"),
        Property("namespace", "Text", "Namespace of the catalog holding the template"),
        Property("catalog", "Text", "Key of the catalog holding the template"),
        Property("template", "Text", "Key of the template"),
        Property("variant", "Text", "Key of the template variant"),
        Property("release", "Text", "Version of the catalog release rendered"),
        Property("draft", "Text", "Id of the draft rendered, when no release was"),
        Property("draftLabel", "Text", "Name of the draft rendered"),
        Property("catalogVersion", "Text", "Id of the catalog version rendered"),
        Property("environment", "Text", "Key of the environment the request named"),
        Property("request", "Text", "Id of the generation request"),
        Property("batch", "Text", "Id of the batch the request belongs to"),
        Property("preview", "Boolean", "Whether the document is a preview"),
        Property("renderedAt", "Date", "When the document was rendered (sys.render.time)"),
        Property("locale", "Text", "BCP 47 locale the document was formatted with"),
        Property("engine", "Text", "Rendering engine and rendering defaults version"),
    )

    init {
        XMPMetaFactory.getSchemaRegistry().registerNamespace(NAMESPACE, PREFIX)
    }

    /**
     * Adds [context] to [xmp], with the values the render used: [renderedAt] (ISO 8601, as
     * `sys.render.time` showed it), [locale] and [engine]. A null value is left out.
     */
    fun write(xmp: XMPMeta, context: GenerationContext, renderedAt: String?, locale: String?, engine: String?) {
        val values = mapOf(
            "tenant" to context.tenant,
            "registry" to context.registry,
            "namespace" to context.namespace,
            "catalog" to context.catalog,
            "template" to context.template,
            "variant" to context.variant,
            "release" to context.release,
            "draft" to context.draft,
            "draftLabel" to context.draftLabel,
            "catalogVersion" to context.catalogVersion,
            "environment" to context.environment,
            "request" to context.request,
            "batch" to context.batch,
            "renderedAt" to renderedAt,
            "locale" to locale,
            "engine" to engine,
        )
        values.forEach { (name, value) -> if (value != null) xmp.setProperty(NAMESPACE, name, value) }
        xmp.setPropertyBoolean(NAMESPACE, "preview", context.preview)
    }

    /**
     * Adds the extension schema describing [PROPERTIES] to [xmp]'s `pdfaExtension:schemas`, next to
     * the schemas already there, when [xmp] holds a generation context and no description of it.
     */
    fun describe(xmp: XMPMeta): XMPMeta {
        if (!xmp.doesPropertyExist(NAMESPACE, "tenant")) return xmp
        val schemas = "schemas"
        val count = if (xmp.doesPropertyExist(XMPConst.NS_PDFA_EXTENSION, schemas)) xmp.countArrayItems(XMPConst.NS_PDFA_EXTENSION, schemas) else 0
        val described = (1..count).any { i ->
            xmp.getStructField(XMPConst.NS_PDFA_EXTENSION, XMPPathFactory.composeArrayItemPath(schemas, i), XMPConst.NS_PDFA_SCHEMA, "namespaceURI")?.value == NAMESPACE
        }
        if (described) return xmp

        xmp.appendArrayItem(XMPConst.NS_PDFA_EXTENSION, schemas, PropertyOptions(PropertyOptions.ARRAY), null, PropertyOptions(PropertyOptions.STRUCT))
        val schema = XMPPathFactory.composeArrayItemPath(schemas, count + 1)
        xmp.setStructField(XMPConst.NS_PDFA_EXTENSION, schema, XMPConst.NS_PDFA_SCHEMA, "namespaceURI", NAMESPACE)
        xmp.setStructField(XMPConst.NS_PDFA_EXTENSION, schema, XMPConst.NS_PDFA_SCHEMA, "prefix", PREFIX)
        xmp.setStructField(XMPConst.NS_PDFA_EXTENSION, schema, XMPConst.NS_PDFA_SCHEMA, "schema", "Epistola generation context")
        val properties = schema + XMPPathFactory.composeStructFieldPath(XMPConst.NS_PDFA_SCHEMA, "property")
        PROPERTIES.forEach { property ->
            xmp.appendArrayItem(XMPConst.NS_PDFA_EXTENSION, properties, PropertyOptions(PropertyOptions.ARRAY_ORDERED), null, PropertyOptions(PropertyOptions.STRUCT))
            val item = XMPPathFactory.composeArrayItemPath(properties, XMPConst.ARRAY_LAST_ITEM)
            xmp.setStructField(XMPConst.NS_PDFA_EXTENSION, item, XMPConst.NS_PDFA_PROPERTY, "name", property.name)
            xmp.setStructField(XMPConst.NS_PDFA_EXTENSION, item, XMPConst.NS_PDFA_PROPERTY, "valueType", property.valueType)
            xmp.setStructField(XMPConst.NS_PDFA_EXTENSION, item, XMPConst.NS_PDFA_PROPERTY, "category", "internal")
            xmp.setStructField(XMPConst.NS_PDFA_EXTENSION, item, XMPConst.NS_PDFA_PROPERTY, "description", property.description)
        }
        return xmp
    }
}
