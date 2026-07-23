package app.epistola.suite.features

import app.epistola.suite.common.ids.FeatureKey

object KnownFeatures {
    val SUPPORT_FEEDBACK = FeatureKey.of("support-feedback")
    val SUPPORT_BACKUPS = FeatureKey.of("support-backups")

    /**
     * The compatibility-check feature (Support → Upgrading). The toggle/entitlement key is
     * `support-compatibility-check`; the UI/module is still named "upgrading" internally pending a
     * follow-up rename.
     */
    val SUPPORT_COMPATIBILITY_CHECK = FeatureKey.of("support-compatibility-check")

    /**
     * Quality checks — the findings ledger, its report, and the editor panel.
     *
     * Deliberately named `quality`, not `support-quality`: this is **not** a support-tier feature.
     * The ledger and its in-process check sources work with the support tier off, so the key must
     * stay out of [SUPPORT_TIER] and [HUB_ONLY]. A key in [SUPPORT_TIER] is gated by a hub
     * entitlement, and the hub wire contract has no `QUALITY` feature to grant — so a quality key
     * there would be *permanently unavailable* on every `epistola.support.enabled=true`
     * installation. `KnownFeaturesTest` guards that; note `WireContractAlignmentTest` would **not**
     * (it asserts three named keys, it does not iterate [all]).
     *
     * A later hub *transport* for findings gets its own separate key rather than reclassifying this
     * one — renaming a feature key is a migration (see
     * `V20260618204750__core_rename_compatibility_check_feature_key.sql`).
     */
    val QUALITY = FeatureKey.of("quality")
    val AI_CHAT = FeatureKey.of("ai-chat")

    val all: List<FeatureKey> = listOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_COMPATIBILITY_CHECK, QUALITY, AI_CHAT)

    /**
     * Features whose availability is gated by a hub **entitlement** when the support tier is enabled
     * (the [FeatureEntitlementGate.gatedFeatures] set). With no tier present (OSS) they fall through to
     * their plain toggle — feedback is freely usable, backups/compatibility-check are simply off by default.
     */
    val SUPPORT_TIER: Set<FeatureKey> = setOf(SUPPORT_FEEDBACK, SUPPORT_BACKUPS, SUPPORT_COMPATIBILITY_CHECK)

    /**
     * Support features that are **inert without a live hub** (unlike feedback, which is freely usable
     * locally). Their toggle default follows `epistola.support.enabled`: on with the tier, off in OSS.
     * Resolved in [FeatureToggleService.defaultFor].
     */
    val HUB_ONLY: Set<FeatureKey> = setOf(SUPPORT_BACKUPS, SUPPORT_COMPATIBILITY_CHECK)

    /**
     * Release maturity of a feature. [STABLE] shows no marker; [BETA]/[ALPHA] render a badge in the
     * nav, on the feature page, and in the admin Features list.
     *
     * [label] is the user-facing text and [badgeClass] the design-system CSS class (both null for
     * stable, so the UI renders nothing). A non-stable stage MUST have a matching `.badge-*` rule in
     * `modules/design-system/components.css` — `FeatureStageTest` guards that the enum and CSS agree
     * so a new stage can't ship as an unstyled badge.
     */
    enum class FeatureStage(val label: String?, val badgeClass: String?) {
        STABLE(null, null),
        BETA("Beta", "badge-beta"),
        ALPHA("Alpha", "badge-alpha"),
    }

    /** Display metadata for a feature, shown wherever the feature surfaces in the UI. */
    data class FeatureMetadata(
        val title: String,
        val description: String,
        val stage: FeatureStage = FeatureStage.STABLE,
    )

    val metadata: Map<FeatureKey, FeatureMetadata> = mapOf(
        SUPPORT_FEEDBACK to FeatureMetadata(
            "Feedback",
            "Enables the Support → Feedback feature (sync to epistola-hub).",
        ),
        SUPPORT_BACKUPS to FeatureMetadata(
            "Backups",
            "Enables the Support → Backups feature (faithful full-fidelity tenant backups + restore).",
            stage = FeatureStage.BETA,
        ),
        SUPPORT_COMPATIBILITY_CHECK to FeatureMetadata(
            "Upgrading",
            "Enables the Support → Upgrading feature (compatibility checks against upcoming Epistola releases). " +
                "Ships its own catalog-export snapshots to the hub for the checks.",
        ),
        QUALITY to FeatureMetadata(
            "Quality",
            "Enables quality checks — a ledger of findings about templates, surfaced in a report and " +
                "in the template editor. Findings are submitted by check sources (in-process or remote) " +
                "and by reviewers; checks only ever analyse a template's example data.",
            stage = FeatureStage.ALPHA,
        ),
        AI_CHAT to FeatureMetadata(
            "AI Chat",
            "Enables the alpha AI chat panel in the template editor. The current panel is an " +
                "experimental editor assistant surface and is hidden by default.",
            stage = FeatureStage.ALPHA,
        ),
    )

    fun metadataFor(key: FeatureKey): FeatureMetadata? = metadata[key]

    fun stageOf(key: FeatureKey): FeatureStage = metadata[key]?.stage ?: FeatureStage.STABLE
}
