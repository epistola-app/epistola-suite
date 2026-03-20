# Keycloak Setup Guide

This document explains how to configure Keycloak for use with Epistola Suite.

## Overview

Epistola uses Keycloak's **Group Membership Mapper** to derive tenant roles, global roles, and platform roles from JWT group names. All Epistola groups use the `ep_` prefix.

## Group Naming Convention

| Pattern | Example | Meaning |
|---------|---------|---------|
| `ep_{tenant}_{role}` | `ep_acme-corp_reader` | `reader` role in `acme-corp` |
| `ep_{role}` | `ep_reader` | `reader` role in **all** tenants |
| `ep_tenant-manager` | `ep_tenant-manager` | Platform role: manage tenants |

### Known Roles

**Tenant roles** (per-tenant or global):
- `reader` — view templates, themes, documents
- `editor` — edit templates and themes
- `generator` — generate documents
- `manager` — full tenant management

**Platform roles**:
- `tenant-manager` — create and manage tenants across the platform

### How Parsing Works

After stripping the `ep_` prefix:
- Contains `_` → split on **last** `_` → left = tenant key, right = role
- No `_` → global tenant role or platform role

This is unambiguous because tenant keys match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (no underscores).

### Global Roles

A global role like `ep_reader` grants read access to **all** tenants. Global roles are merged with per-tenant roles. Example:

```
User groups: ["ep_acme-corp_editor", "ep_reader"]

Effective roles:
  acme-corp: {READER, EDITOR}   (per-tenant EDITOR + global READER)
  any-tenant: {READER}          (from global)
```

## Keycloak Configuration

### 1. Client Setup

Create an OpenID Connect client named `epistola-suite`:
- Client authentication: ON (confidential)
- Standard flow: ON
- Service accounts: ON (needed for admin API)
- Redirect URIs: your app URL (e.g., `http://localhost:4000/*`)

### 2. Group Membership Mapper

Add a protocol mapper to the `epistola-suite` client:

| Setting | Value |
|---------|-------|
| Name | `group-membership-mapper` |
| Mapper type | `Group Membership` |
| Token claim name | `groups` |
| Full group path | OFF |
| Add to ID token | ON |
| Add to access token | ON |
| Add to userinfo | ON |

### 3. Create Groups

For each tenant, create groups:
- `ep_{tenant-key}_reader`
- `ep_{tenant-key}_editor`
- `ep_{tenant-key}_generator`
- `ep_{tenant-key}_manager`

For platform roles:
- `ep_tenant-manager`

For global roles (optional):
- `ep_reader`, `ep_editor`, etc.

### 4. Assign Users to Groups

Assign users to the appropriate groups based on their roles.

## Automatic Tenant Provisioning

When `epistola.keycloak.client-secret` is configured, Epistola automatically creates Keycloak groups when a new tenant is created via the UI. The four standard groups (`ep_{key}_reader`, `ep_{key}_editor`, `ep_{key}_generator`, `ep_{key}_manager`) are created automatically.

### Configuration

```yaml
epistola:
  keycloak:
    admin-url: http://localhost:8080       # Keycloak base URL
    realm: epistola                         # Realm name
    client-id: epistola-suite              # Client with service account
    client-secret: ${KEYCLOAK_CLIENT_SECRET}  # Client secret
```

The `epistola-suite` client needs service account roles for group management:
- `manage-users` realm role (or scoped `manage-groups` if available)

## Local Development

Start Keycloak with the provided Docker Compose setup:

```bash
docker compose -f apps/epistola/docker/keycloak/docker-compose.yaml up -d
```

Run the app with the `keycloak` profile:

```bash
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local,keycloak'
```

Test users (pre-configured in realm export):
- `admin@test / admin` — all demo-tenant roles + tenant-manager
- `user@test / user` — reader, editor, generator in demo-tenant

## JWT Claim Example

After login, the JWT will contain:

```json
{
  "groups": [
    "ep_demo-tenant_reader",
    "ep_demo-tenant_editor",
    "ep_demo-tenant_generator",
    "ep_demo-tenant_manager",
    "ep_tenant-manager"
  ]
}
```
