package app.epistola.suite.backup

import org.springframework.stereotype.Component

/**
 * Classifies epistola-core's own tenant-scoped tables for tenant backup/restore.
 * Core owns the bulk of the schema, so this is the largest contribution; feature
 * modules declare their own tables via their own [TenantBackupTableContributor]s.
 *
 * "What's in a backup" is a data-fidelity and security decision, so the two sets
 * are the hand-maintained source of truth for core's tables — kept auditable in one
 * place, and enforced by the drift guard (every discovered tenant table must be
 * classified). `tenants` is included but update-in-place on restore (never deleted).
 */
@Component
class CoreBackupTables : TenantBackupTableContributor {
    /** Tenant-scoped tables that ARE backed up and merge-restored (FK order derived at runtime). */
    override fun includedTables(): Set<String> = setOf(
        "tenants",
        "catalogs",
        "catalog_releases",
        "themes",
        "document_templates",
        "template_variants",
        "template_versions",
        "contract_versions",
        "stencils",
        "stencil_versions",
        "code_lists",
        "code_list_entries",
        "fonts",
        "font_variants",
        "assets",
        "variant_attribute_definitions",
        "environments",
        "environment_activations",
        "api_keys",
        "feature_toggles",
        // Quality: machine findings ride along, but the human half — an ignore with its
        // stated reason, a manual review finding, a comment — is authoring intent and
        // must survive a restore.
        "quality_findings",
        "quality_finding_ignores",
        "quality_finding_comments",
    )

    /**
     * Tenant-scoped tables deliberately NOT backed up and NEVER touched by restore — generated
     * documents (regenerable), the append-only collect feed and its cursors (must survive and stay
     * monotonic for external consumers), and audit/runtime/membership tables.
     */
    override fun excludedTables(): Set<String> = setOf(
        "documents",
        "document_generation_requests",
        "document_generation_batches",
        "generation_results",
        "consumer_partition_cursors",
        "consumer_node_assignments",
        "event_log",
        "application_log",
        "tenant_memberships",
        "cluster_timers",
        "cluster_tasks_scheduled",
    )
}
