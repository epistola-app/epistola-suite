# Feature Toggles

## Overview

Epistola supports per-tenant feature toggles, allowing tenant managers to enable or disable optional features for their tenant. This provides fine-grained control over which capabilities are available without code changes or redeployment.

## Architecture

Feature toggles use a **two-tier resolution** model:

1. **Global defaults** — configured in `application.yaml` under `epistola.features.*`
2. **Tenant overrides** — stored in the `feature_toggles` database table

When checking whether a feature is enabled, the system first looks for a tenant-specific override. If none exists, it falls back to the global default. If neither is configured, the feature defaults to disabled.

```
Request → FeatureToggleService.isEnabled(tenantKey, featureKey)
            ├── DB override exists? → use it
            └── No override → FeatureDefaults.isEnabled(featureKey) → YAML config value
```

## Key Classes

| Class                  | Location                                       | Purpose                                           |
| ---------------------- | ---------------------------------------------- | ------------------------------------------------- |
| `KnownFeatures`        | `modules/epistola-core/.../features/`          | Registry of all feature keys with descriptions    |
| `FeatureDefaults`      | `modules/epistola-core/.../features/`          | Global defaults from `application.yaml`           |
| `FeatureToggleService` | `modules/epistola-core/.../features/`          | Resolves effective state (DB override or default) |
| `FeatureKey`           | `modules/epistola-core/.../common/ids/`        | Typed value class with slug validation            |
| `SaveFeatureToggle`    | `modules/epistola-core/.../features/commands/` | Upserts a tenant override                         |
| `DeleteFeatureToggle`  | `modules/epistola-core/.../features/commands/` | Removes a tenant override (reverts to default)    |
| `GetFeatureToggles`    | `modules/epistola-core/.../features/queries/`  | Returns all features with resolved state          |

## How to Add a New Feature Toggle

1. **Register the feature** in `KnownFeatures`:

   ```kotlin
   val MY_FEATURE = FeatureKey.of("my-feature")
   val all: List<FeatureKey> = listOf(FEEDBACK, MY_FEATURE)
   val descriptions: Map<FeatureKey, String> = mapOf(
       FEEDBACK to "Enables feedback option.",
       MY_FEATURE to "Description of my feature.",
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

## Current Features

| Key        | Description                                                  | Default |
| ---------- | ------------------------------------------------------------ | ------- |
| `feedback` | Enables the feedback system (nav link, FAB, console capture) | `true`  |
