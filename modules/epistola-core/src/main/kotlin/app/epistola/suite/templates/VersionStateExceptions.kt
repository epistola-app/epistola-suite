// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey

class VersionNotDraftException(
    val tenantId: TenantKey,
    val versionId: VersionKey,
) : RuntimeException("Version $versionId is not a draft and cannot be modified")

class VersionNotPublishedException(
    val tenantId: TenantKey,
    val versionId: VersionKey,
) : RuntimeException("Version $versionId is not published and cannot be archived")

class VersionArchivedException(
    val tenantId: TenantKey,
    val versionId: VersionKey,
) : RuntimeException("Version $versionId is archived and cannot be published")

class DraftHasNoPublishedBaseException(
    val tenantId: TenantKey,
    val variantId: VariantKey,
) : RuntimeException("Variant $variantId has no published version to revert to; the draft cannot be discarded")
