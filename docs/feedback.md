# Feedback System with GitHub Integration

## Overview

Users can report issues, request features, and ask questions from within Epistola. Feedback is stored locally in PostgreSQL (source of truth) and optionally synced to a tenant-configured GitHub repository via a GitHub App. Users are unaware of the GitHub backend. Admins and developers reply via GitHub; responses sync back to Epistola via webhooks.

## Architecture Decisions

### Local-first storage
The local Postgres database is the primary data store. GitHub is a sync target, not the source of truth. This is implemented via a port/adapter pattern (`IssueSyncPort`) to allow future backends (e.g., Jira, Linear).

### GitHub App authentication
Authentication with GitHub uses a GitHub App (not personal access tokens). The app is installed per-organization and generates short-lived installation tokens. This provides:
- Fine-grained repository permissions
- No dependency on individual user accounts
- Webhook support for inbound sync

### Shared repos via labels
Multiple tenants can share a single GitHub repository. Issues are distinguished by tenant-specific labels (e.g., `tenant:acme`). This avoids requiring a separate repo per tenant.

### Access control
- **Feedback list**: visible to all authenticated tenant members
- **Feedback detail** (description, console logs, screenshot): visible to the creator and tenant admins only
- **Feedback config**: admin only
- **Roles**: per-tenant, sourced from JWT `epistola_tenants` claim (MEMBER, ADMIN)

### Naming
The feature is called **"Feedback"** internally to avoid collision with GitHub's "Issues" terminology.

## Data Model

### feedback_config
Per-tenant configuration for GitHub integration.

| Column | Type | Description |
|--------|------|-------------|
| tenant_key | TEXT PK | Tenant identifier |
| enabled | BOOLEAN | Whether sync is active |
| installation_id | BIGINT | GitHub App installation ID |
| repo_owner | TEXT | GitHub repository owner |
| repo_name | TEXT | GitHub repository name |
| label | TEXT | Label prefix for this tenant |

All GitHub fields must be set together or all null (CHECK constraint).

### feedback
Core feedback entity.

| Column | Type | Description |
|--------|------|-------------|
| tenant_key | TEXT | Tenant identifier |
| id | UUID | Feedback identifier (UUIDv7) |
| title | TEXT | Short summary |
| description | TEXT | Detailed description |
| category | TEXT | BUG, FEATURE_REQUEST, QUESTION, OTHER |
| status | TEXT | OPEN, IN_PROGRESS, RESOLVED, CLOSED |
| priority | TEXT | LOW, MEDIUM, HIGH, CRITICAL |
| source_url | TEXT | Page URL where feedback was submitted |
| screenshot_key | UUID | Reference to assets table (nullable) |
| console_logs | TEXT | Captured browser console output (nullable) |
| created_by | UUID | User who submitted the feedback |
| created_at | TIMESTAMPTZ | Creation timestamp |
| updated_at | TIMESTAMPTZ | Last update timestamp |
| external_ref | TEXT | GitHub issue number (nullable) |
| external_url | TEXT | GitHub issue URL (nullable) |
| sync_status | TEXT | PENDING, SYNCED, FAILED, NOT_CONFIGURED |

PK: `(tenant_key, id)`

### feedback_comments
Comments on feedback items.

| Column | Type | Description |
|--------|------|-------------|
| tenant_key | TEXT | Tenant identifier |
| feedback_id | UUID | Parent feedback ID |
| id | UUID | Comment identifier (UUIDv7) |
| body | TEXT | Comment content |
| author_name | TEXT | Display name of author |
| author_email | TEXT | Email of author (nullable) |
| source | TEXT | LOCAL or GITHUB |
| external_comment_id | BIGINT | GitHub comment ID for dedup (nullable) |
| created_at | TIMESTAMPTZ | Creation timestamp |

PK: `(tenant_key, feedback_id, id)`. Unique index on `external_comment_id` for dedup.

## CQRS Operations

### Commands
| Command | Purpose |
|---------|---------|
| CreateFeedback | Submit new feedback |
| UpdateFeedbackStatus | Change feedback status (admin/creator) |
| AddFeedbackComment | Add a local comment |
| SyncFeedbackComment | Ingest a GitHub comment (dedup) |
| UpdateFeedbackSyncRef | Store GitHub issue ref after sync |
| SaveFeedbackConfig | Configure GitHub integration (admin) |

### Queries
| Query | Access | Returns |
|-------|--------|---------|
| ListFeedback | All members | FeedbackSummary list |
| GetFeedback | Admin or creator | Full Feedback |
| GetFeedbackComments | Admin or creator | Comment list |
| GetFeedbackConfig | Admin | Config |
| ListPendingSyncFeedback | System | Feedback list for retry |

## UI Components

### Floating Action Button (FAB)
A fixed-position button on tenant-scoped pages for quick feedback submission. Opens a modal dialog.

### Submit Dialog
Modal with fields: title, category, priority, description. Auto-captures source URL and console logs. Optional screenshot upload.

### Feedback List Page
`/tenants/{tenantId}/feedback` — filterable table showing all feedback for the tenant. Visible to all members.

### Feedback Detail Page
`/tenants/{tenantId}/feedback/{id}` — full details including description, screenshot, console logs, and comment timeline. Restricted to admin or creator.

### Console Capture
JavaScript module that monkey-patches `console.*` methods to buffer the last 100 entries. Automatically included in feedback submissions.

## GitHub Integration

### Outbound Sync (Epistola → GitHub)
- Triggered after feedback creation (event listener, fires `AFTER_COMMIT`)
- Creates a GitHub issue with description, source URL, console logs, and screenshot
- Adds tenant label (e.g., `tenant:acme`)
- On failure: sync_status stays PENDING, retried by scheduled job

### Inbound Sync (GitHub → Epistola)
- Webhook endpoint at `/webhooks/github` (infrastructure endpoint, separate security filter chain)
- HMAC-SHA256 signature verification
- Processes `issue_comment` events → `SyncFeedbackComment`
- Processes `issues` closed/reopened → `UpdateFeedbackStatus`
- Tenant lookup: match repo + label against `feedback_config`

### GitHub App Auth
- RS256 JWT signed with app private key
- Exchanged for installation access token (1 hour TTL)
- Token cached with auto-refresh at 50 minutes

## Configuration

```yaml
epistola:
  feedback:
    github:
      enabled: false
      app-id: ${GITHUB_APP_ID:}
      private-key-path: ${GITHUB_APP_PRIVATE_KEY_PATH:}
      webhook-secret: ${GITHUB_WEBHOOK_SECRET:}
```

## Implementation Phases

1. **Phase 0**: Per-tenant roles from JWT (TenantRole enum, update EpistolaPrincipal)
2. **Phase 1**: Core feedback domain (models, DB migration, CQRS commands/queries)
3. **Phase 2**: Feedback UI (FAB, dialog, list page, detail page)
4. **Phase 3**: GitHub App integration (port/adapter, outbound sync, admin config)
5. **Phase 4**: GitHub webhooks (inbound sync)
6. **Phase 5**: Polish (pagination, toasts, error handling, rate limiting)
