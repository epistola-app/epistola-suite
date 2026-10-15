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

/** A request names a release of the template's catalog that does not exist or kept no content to render. */
class ReleaseNotFoundException(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val releaseVersion: String,
) : RuntimeException("Catalog '${catalogKey.value}' has no release $releaseVersion that can be rendered.")

/** A request names an environment that serves no release of the template's catalog. */
class NoReleaseDeployedException(
    val tenantKey: TenantKey,
    val environmentKey: EnvironmentKey,
    val catalogKey: CatalogKey,
) : RuntimeException(
    "Environment '${environmentKey.value}' serves no release of catalog '${catalogKey.value}'. Deploy a release there first.",
)
