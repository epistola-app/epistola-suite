// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey

/**
 * Thrown when a template/variant combination does not exist for a tenant.
 */
class TemplateVariantNotFoundException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : RuntimeException("Template $templateId variant $variantId not found for tenant $tenantId")

/**
 * Thrown when a template version does not exist.
 */
class VersionNotFoundException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val versionId: VersionKey,
) : RuntimeException("Version $versionId not found for template $templateId variant $variantId")

/**
 * Thrown when an environment does not exist for a tenant.
 */
class EnvironmentNotFoundException(
    val tenantId: TenantKey,
    val environmentId: EnvironmentKey,
) : RuntimeException("Environment $environmentId not found for tenant $tenantId")

/**
 * Thrown when no default variant exists for a template.
 */
class DefaultVariantNotFoundException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
) : RuntimeException("No default variant found for template $templateId in tenant $tenantId")

/**
 * Thrown when no published version exists for a variant.
 */
class NoPublishedVersionException(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : RuntimeException("No published version found for template $templateId variant $variantId in tenant $tenantId. Import a catalog or publish a version first.")

/**
 * Generation renders a catalog release, and this catalog has none that kept its content.
 *
 * From 2.0 nothing renders from the working copy or from a template version: an author releases the
 * catalog, and generation reads that release.
 */
class CatalogNotReleasedException(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : RuntimeException("Catalog '${catalogKey.value}' has no release to generate from. Release the catalog first.")

/** The release being generated from does not contain the requested template or variant. */
class TemplateNotInReleaseException(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val releaseVersion: String,
    val templateId: TemplateKey,
    val variantId: VariantKey?,
) : RuntimeException(
    if (variantId == null) {
        "Release ${catalogKey.value}@$releaseVersion does not contain template '${templateId.value}'"
    } else {
        "Release ${catalogKey.value}@$releaseVersion has no variant '${variantId.value}' of template '${templateId.value}'"
    },
)

/**
 * Generating or previewing a specific template version is gone in 2.0: a request renders a catalog
 * release. Raised as a validation error so every surface reports it as a bad request, naming the
 * field that caused it.
 */
fun versionGenerationRemoved(): app.epistola.suite.validation.ValidationException = app.epistola.suite.validation.ValidationException(
    "versionId",
    "Generating a specific template version is no longer supported: generation renders the catalog's latest release, " +
        "or the release deployed to an environment. Leave versionId out.",
)
