# Feature Toggles

## Overview

Epistola supports per-tenant feature toggles, allowing tenant managers to enable or disable optional features for their tenant. This provides fine-grained control over which capabilities are available without code changes or redeployment.

## Architecture

Feature toggles use a **two-tier resolution** model:

1. **Global defaults** — configured in `application.yaml` under `epistola.features.*`
2. **Tenant overrides** — stored in the `feature_toggles` database table

When checking whether a feature is enabled, the system first looks for a tenant-specific override. If none exists, it falls back to the global default. If neither is configured, the feature defaults to disabled.

**Hub-only support features** (`KnownFeatures.HUB_ONLY` — backups / upgrading) are the exception: they are not configured in `FeatureDefaults`. Their global default follows the support tier — **on when `epistola.support.enabled=true`, off otherwise (OSS)** — because they are inert without a live hub (the same principle applies to telemetry; see [`adr/0006-shipping-logs-and-metrics-to-hub.md`](adr/0006-shipping-logs-and-metrics-to-hub.md)). **Feedback is not hub-only** — it is freely usable locally, so it keeps a plain `FeatureDefaults` default of **on**. When the support tier _is_ enabled, the hub entitlement gates actual availability of all three (`SUPPORT_TIER` = the `gatedFeatures` set) on top of the toggle; with no tier present, availability is just the toggle.

```
Request → FeatureToggleService.isEnabled(tenantKey, featureKey)
            ├── DB override exists? → use it
            └── No override → default:
                  ├── support-tier feature → epistola.support.enabled
                  └── otherwise            → FeatureDefaults.isEnabled(featureKey) → YAML config value
```

## Key Classes

| Class                  | Location                                       | Purpose                                                                              |
| ---------------------- | ---------------------------------------------- | ------------------------------------------------------------------------------------ |
| `KnownFeatures`        | `modules/epistola-core/.../features/`          | Registry of all feature keys + display metadata (title, description, maturity stage) |
| `FeatureDefaults`      | `modules/epistola-core/.../features/`          | Global defaults from `application.yaml`                                              |
| `FeatureToggleService` | `modules/epistola-core/.../features/`          | Resolves effective state (DB override or default)                                    |
| `FeatureKey`           | `modules/epistola-core/.../common/ids/`        | Typed value class with slug validation                                               |
| `SaveFeatureToggle`    | `modules/epistola-core/.../features/commands/` | Upserts a tenant override                                                            |
| `DeleteFeatureToggle`  | `modules/epistola-core/.../features/commands/` | Removes a tenant override (reverts to default)                                       |
| `GetFeatureToggles`    | `modules/epistola-core/.../features/queries/`  | Returns all features with resolved state                                             |

## How to Add a New Feature Toggle

1. **Register the feature** in `KnownFeatures` (key + display metadata). The optional `stage`
   defaults to `STABLE`; set `BETA`/`ALPHA` to render a maturity badge (see
   [Feature maturity](#feature-maturity-alpha--beta)):

   ```kotlin
   val MY_FEATURE = FeatureKey.of("my-feature")
   val all: List<FeatureKey> = listOf(FEEDBACK, MY_FEATURE)
   val metadata: Map<FeatureKey, FeatureMetadata> = mapOf(
       FEEDBACK to FeatureMetadata("Feedback", "Enables the feedback option."),
       MY_FEATURE to FeatureMetadata("My feature", "Description of my feature.", stage = FeatureStage.BETA),
   )
   ```

2. **Add the default** in `FeatureDefaults`:

   ```kotlin
   data class FeatureDefaults(
       val feedback: Boolean = false,
       val myFeature: Boolean = false,
   ) {
       fun isEnabled(featureKey: FeatureKey): Boolean = when (featureKey) {
           KnownFeatures.FEEDBACK -> feedback
           KnownFeatures.MY_FEATURE -> myFeature
           else -> false
       }
   }
   ```

3. **Set the global default** in `application.yaml`:

   ```yaml
   epistola:
     features:
       feedback: true
       my-feature: false
   ```

4. **Use the toggle** in your code:
   - **Kotlin (service layer)**: Inject `FeatureToggleService` and call `isEnabled(tenantKey, featureKey)`
   - **Thymeleaf templates**: The `ShellModelInterceptor` exposes toggle state as model attributes (e.g., `feedbackEnabled`). Add new attributes there for template-level gating.

## Database Schema

```sql
CREATE DOMAIN FEATURE_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

CREATE TABLE feature_toggles (
    tenant_key  TENANT_KEY  NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    feature_key FEATURE_KEY NOT NULL,
    enabled     BOOLEAN     NOT NULL,
    PRIMARY KEY (tenant_key, feature_key)
);
```

The `FEATURE_KEY` domain enforces the same slug pattern as the Kotlin `FeatureKey` value class: lowercase letters, digits, and non-consecutive hyphens, 3-50 characters.

## UI

The feature toggles management page is at `/tenants/{tenantId}/features` (Settings > Features in the navigation). It requires the `TENANT_SETTINGS` permission (manager role).

## Access Control

| Operation             | Permission        |
| --------------------- | ----------------- |
| View feature toggles  | `TENANT_SETTINGS` |
| Save feature toggles  | `TENANT_SETTINGS` |
| Delete feature toggle | `TENANT_SETTINGS` |

## Feature maturity (Alpha / Beta)

A feature can advertise its release maturity via `FeatureMetadata.stage`
(`KnownFeatures.FeatureStage`):

| Stage    | Badge    | Meaning                                 |
| -------- | -------- | --------------------------------------- |
| `STABLE` | _(none)_ | Default — no marker is shown.           |
| `BETA`   | Beta     | Feature-complete but still stabilizing. |
| `ALPHA`  | Alpha    | Experimental / less stable.             |

A non-stable stage renders a small badge **on every surface the feature appears
on**: its nav item (desktop + mobile dropdowns), its own page header, and the
admin Features list. The stage is the single source of truth — set it once in
`KnownFeatures.metadata` and all three surfaces pick it up.

Each non-stable stage carries both its user-facing `label` and the design-system
`badgeClass` (e.g. `BETA → "badge-beta"`). The templates use `stage.badgeClass`
directly (no string-building), and the matching `.badge-*` rule lives in
`modules/design-system/components.css`. `FeatureStageTest` fails the build if a
stage is missing its CSS rule, so a new stage cannot ship as an unstyled badge.

To mark a feature beta, just set `stage = FeatureStage.BETA` on its
`FeatureMetadata` entry — nothing else is required. To **add a new stage**:
add the enum constant (with `label` + `badgeClass`) and a matching `.badge-*`
rule in `components.css`.

## Current Features

| Key                           | Description                                                          | Stage  | Default |
| ----------------------------- | -------------------------------------------------------------------- | ------ | ------- |
| `support-feedback`            | Support → Feedback (local; hub sync gated on the support tier)       | Stable | `true`  |
| `support-backups`             | Support → Backups (faithful full-fidelity tenant backups + restore)  | Beta   | tier\*  |
| `support-compatibility-check` | Support → Upgrading (compatibility checks against upcoming releases) | Stable | tier\*  |
| `quality`                     | Quality checks ledger, report, and template-editor panel             | Alpha  | `false` |
| `ai-chat`                     | AI chat panel in the template editor                                 | Alpha  | `false` |

\* Hub-only features (`KnownFeatures.HUB_ONLY`) default to `epistola.support.enabled` — see above.
