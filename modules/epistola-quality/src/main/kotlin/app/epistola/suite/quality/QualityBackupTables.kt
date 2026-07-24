// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.backup.TenantBackupTableContributor
import org.springframework.stereotype.Component

/**
 * Declares the quality feature's tenant-scoped tables to tenant backup/restore.
 *
 * **Included** (merge-restored), not excluded. Machine findings ride along cheaply, but the
 * human half is the point: an ignore carries a person's stated reason for dismissing a
 * finding, a manual finding is a reviewer's note to a colleague, and a comment is the
 * discussion around it. That is authoring intent and must survive a restore — the machine
 * findings would simply be re-reported on the next check anyway.
 *
 * The quality module owns this classification rather than the backup module hard-coding its
 * table names, and rather than core's [app.epistola.suite.backup.CoreBackupTables] carrying
 * tables it does not own.
 */
@Component
class QualityBackupTables : TenantBackupTableContributor {
    override fun includedTables(): Set<String> = setOf(
        "quality_findings",
        "quality_finding_ignores",
        "quality_finding_comments",
    )
}
