// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.installation

import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import org.springframework.stereotype.Service

/**
 * Reads the installation identity from `app_metadata`. The row is seeded
 * exactly once by Flyway (see V30__app_metadata_jsonb.sql) and is invariant
 * for the lifetime of the database — every pod reads the same value.
 *
 * A missing row is a bug (someone deleted the metadata) and must surface
 * loudly. Silently regenerating it would assign a fresh id and confuse the
 * hub, which has the previous id on record.
 */
@Service
class InstallationService(
    private val metadata: AppMetadataService,
) {
    fun get(): Installation = metadata.getAs<Installation>(METADATA_KEY)
        ?: error(
            "Installation identity missing from app_metadata under key '$METADATA_KEY'. " +
                "Was Flyway migration V30 ever applied?",
        )

    companion object {
        const val METADATA_KEY = "installation"
    }
}
