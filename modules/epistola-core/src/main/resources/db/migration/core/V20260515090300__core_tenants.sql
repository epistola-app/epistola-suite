-- Domain type for tenant identifiers (slugs)
CREATE DOMAIN TENANT_KEY AS VARCHAR(63)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Domain type for catalog identifiers (slugs) — used by all resource tables from V5 onward
CREATE DOMAIN CATALOG_KEY AS VARCHAR(50)
    CHECK (VALUE ~ '^[a-z][a-z0-9]*(-[a-z0-9]+)*$');

-- Tenants table for multi-tenancy support
-- IDs are client-provided slugs (e.g., "acme-corp") for human-readable, URL-safe identifiers
CREATE TABLE tenants (
    id TENANT_KEY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    default_theme_catalog_key CATALOG_KEY, -- FK added in V5 after themes table exists
    default_theme_key VARCHAR(20),  -- FK added in V5 after themes table exists
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_name ON tenants(name);

COMMENT ON TABLE tenants IS 'Top-level organizational units for multi-tenancy. Each tenant has isolated data.';
COMMENT ON COLUMN tenants.id IS 'URL-safe slug identifier (e.g., acme-corp)';
COMMENT ON COLUMN tenants.name IS 'Human-readable display name';
COMMENT ON COLUMN tenants.default_theme_key IS 'Fallback theme applied when templates and variants do not specify one. FK added in V5.';
COMMENT ON COLUMN tenants.created_at IS 'When the tenant was created';

-- ============================================================================
-- TENANT MEMBERSHIPS
-- ============================================================================

-- Tenant memberships (many-to-many between users and tenants)
-- Roles are sourced from Keycloak (JWT claim) and synced to the DB
-- for offline queries, audit trails, and API key fallback.
CREATE TABLE tenant_memberships (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    roles VARCHAR(20)[] NOT NULL DEFAULT ARRAY['READER']::VARCHAR[],
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_synced_at TIMESTAMPTZ,
    PRIMARY KEY (user_id, tenant_key)
);

CREATE INDEX idx_tenant_memberships_user_id ON tenant_memberships(user_id);
CREATE INDEX idx_tenant_memberships_tenant_key ON tenant_memberships(tenant_key);

COMMENT ON TABLE tenant_memberships IS 'Many-to-many link between users and tenants they can access';
COMMENT ON COLUMN tenant_memberships.user_id IS 'FK to users.id';
COMMENT ON COLUMN tenant_memberships.tenant_key IS 'FK to tenants.id';
COMMENT ON COLUMN tenant_memberships.roles IS 'Composable tenant roles: READER, EDITOR, GENERATOR, MANAGER. Synced from Keycloak JWT claim.';
COMMENT ON COLUMN tenant_memberships.joined_at IS 'When the user was granted access to this tenant';
COMMENT ON COLUMN tenant_memberships.last_synced_at IS 'When this membership was last confirmed from the IDP (JWT claim sync).';

-- ============================================================================
-- CATALOGS
-- ============================================================================

-- Catalogs are organizational containers for resources. Every resource belongs to exactly one catalog.
-- Authored catalogs are created locally; subscribed catalogs track an external source.
CREATE TABLE catalogs (
    id CATALOG_KEY NOT NULL,
    tenant_key TENANT_KEY NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(20) NOT NULL CHECK (type IN ('AUTHORED', 'SUBSCRIBED')),
    source_url TEXT,
    source_auth_type VARCHAR(20) DEFAULT 'NONE' CHECK (source_auth_type IN ('NONE', 'API_KEY', 'BEARER')),
    source_auth_credential TEXT,
    installed_release_version VARCHAR(50),
    installed_fingerprint CHAR(64),
    installed_resource_fingerprints JSONB,
    installed_at TIMESTAMPTZ,
    released_version VARCHAR(50),
    released_fingerprint CHAR(64),
    released_at TIMESTAMPTZ,
    imported_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_key, id)
);

COMMENT ON TABLE catalogs IS 'Organizational containers for resources. Every resource belongs to exactly one catalog.';
COMMENT ON COLUMN catalogs.type IS 'AUTHORED = created locally and editable, SUBSCRIBED = installed from external source and read-only';
COMMENT ON COLUMN catalogs.source_url IS 'Remote catalog manifest URL (SUBSCRIBED only)';
COMMENT ON COLUMN catalogs.installed_release_version IS 'Version of the currently installed release (SUBSCRIBED only)';
COMMENT ON COLUMN catalogs.installed_fingerprint IS 'Content fingerprint (SHA-256 hex) of the currently installed release (SUBSCRIBED only). Drift/upgrade detection: differs from the source manifest fingerprint => content changed.';
COMMENT ON COLUMN catalogs.installed_resource_fingerprints IS 'Per-resource source-side digests ("type/slug" -> SHA-256 hex) of the installed release, captured from the source manifest at register/upgrade (mirrors installed_fingerprint, never publisher-authored). Source-vs-source baseline for the upgrade preview''s ADDED/REMOVED/CHANGED/UNCHANGED diff. SUBSCRIBED only.';
COMMENT ON COLUMN catalogs.released_version IS 'Latest released SemVer of an AUTHORED catalog (pointer into catalog_releases). NULL = never released. SUBSCRIBED catalogs use installed_release_version instead.';
COMMENT ON COLUMN catalogs.released_fingerprint IS 'Content fingerprint of the latest AUTHORED release (denormalized from catalog_releases for O(1) read surfaces). NULL = never released.';
COMMENT ON COLUMN catalogs.released_at IS 'When the latest AUTHORED release was cut (mirrors the catalog_releases row).';
COMMENT ON COLUMN catalogs.imported_at IS 'When catalog content was last set wholesale by a ZIP import (set at the end of ImportCatalogZip, after resource upserts). With released_at it forms the AUTHORED drift baseline GREATEST(released_at, imported_at): a resource updated_at beyond it = unreleased working-copy changes. Set to NOW() by every import so a no-op re-import does not register as drift.';
COMMENT ON COLUMN catalogs.created_at IS 'When the catalog was created';
COMMENT ON COLUMN catalogs.updated_at IS 'When the catalog was last updated';

-- updated_at is DB-enforced by the shared set_updated_at() trigger function.
CREATE TRIGGER trg_catalogs_updated_at
    BEFORE UPDATE ON catalogs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- CATALOG RELEASES
-- ============================================================================

-- Immutable release boundaries for AUTHORED catalogs. One row per explicit
-- "Release version" action: author-set SemVer + deterministic content
-- fingerprint + notes + a manifest snapshot captured at release time. This is
-- release *history* (changelog / upgrade-diff source), NOT parallel installs —
-- a catalog still has exactly one live working copy.
CREATE TABLE catalog_releases (
    tenant_key TENANT_KEY NOT NULL,
    catalog_key CATALOG_KEY NOT NULL,
    version VARCHAR(50) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    notes TEXT,
    manifest_snapshot JSONB NOT NULL,
    released_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    released_by UUID,
    PRIMARY KEY (tenant_key, catalog_key, version),
    FOREIGN KEY (tenant_key, catalog_key) REFERENCES catalogs (tenant_key, id) ON DELETE CASCADE
);

CREATE INDEX idx_catalog_releases_lookup
    ON catalog_releases (tenant_key, catalog_key, released_at DESC);

COMMENT ON TABLE catalog_releases IS 'Immutable release boundaries for AUTHORED catalogs: author-set SemVer + content fingerprint + notes + manifest snapshot. One row per Release action; release history, not parallel installs.';
COMMENT ON COLUMN catalog_releases.version IS 'Author-set SemVer (MAJOR.MINOR.PATCH). Strictly increases per catalog.';
COMMENT ON COLUMN catalog_releases.fingerprint IS 'Lowercase hex SHA-256 of the catalog canonical content at release time.';
COMMENT ON COLUMN catalog_releases.manifest_snapshot IS 'Full CatalogManifest + resource details captured at release (Phase 2 upgrade-diff source).';
COMMENT ON COLUMN catalog_releases.released_by IS 'users.id of the releasing user; NULL for system/bootstrap releases. No FK to avoid cross-table ordering coupling.';
