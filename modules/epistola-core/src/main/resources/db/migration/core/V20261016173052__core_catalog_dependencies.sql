-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A catalog declares each catalog it depends on once, with a version selector in the npm range
-- grammar (contract ADR 0003). A declaration names the catalog (registry, namespace, key) rather
-- than referencing a row: an imported catalog may depend on one the tenant does not have yet.
--
-- Each declaration is locked to one release of the dependency, like a lockfile: the release the
-- working copy's next release is cut with. A lock moves only when the declaration changes or the
-- author resolves again, never because the dependency released; an import takes the archive's.
-- A declaration no release matched yet has no lock, and both lock columns stay null together.
CREATE TABLE catalog_dependencies (
    tenant_key          TENANT_KEY   NOT NULL,
    catalog_id          UUID         NOT NULL,
    registry            TEXT         NOT NULL,
    namespace           TEXT         NOT NULL,
    dependency_key      VARCHAR(50)  NOT NULL,
    version_selector    VARCHAR(100) NOT NULL,
    -- Declaration order, which the manifest keeps.
    position            INT          NOT NULL,
    release_version     VARCHAR(100),
    release_fingerprint VARCHAR(80),
    PRIMARY KEY (tenant_key, catalog_id, registry, namespace, dependency_key),
    FOREIGN KEY (tenant_key, catalog_id) REFERENCES catalogs (tenant_key, catalog_id) ON DELETE CASCADE,
    CONSTRAINT chk_catalog_dependencies_locked CHECK ((release_version IS NULL) = (release_fingerprint IS NULL))
);

COMMENT ON TABLE catalog_dependencies IS
    'The catalogs a catalog depends on, each declared once with a version selector and locked to one release (wire v8 catalogDependencies).';
COMMENT ON COLUMN catalog_dependencies.release_version IS
    'The release the declaration is locked to; null while no release of the dependency matched the selector.';

-- What each release was cut with: every declaration, and the release of the dependency it resolved
-- to with that release''s fingerprint. A release renders with exactly these releases. A release
-- imported from an archive of wire v7 or earlier never recorded one, so both stay null together.
CREATE TABLE catalog_release_dependencies (
    tenant_key          TENANT_KEY   NOT NULL,
    catalog_id          UUID         NOT NULL,
    version             VARCHAR(50)  NOT NULL,
    registry            TEXT         NOT NULL,
    namespace           TEXT         NOT NULL,
    dependency_key      VARCHAR(50)  NOT NULL,
    version_selector    VARCHAR(100) NOT NULL,
    position            INT          NOT NULL,
    release_version     VARCHAR(100),
    release_fingerprint VARCHAR(80),
    PRIMARY KEY (tenant_key, catalog_id, version, registry, namespace, dependency_key),
    CONSTRAINT fk_catalog_release_dependencies_release
        FOREIGN KEY (tenant_key, catalog_id, version) REFERENCES catalog_releases (tenant_key, catalog_id, version) ON DELETE CASCADE,
    CONSTRAINT chk_catalog_release_dependencies_pinned CHECK ((release_version IS NULL) = (release_fingerprint IS NULL))
);

COMMENT ON TABLE catalog_release_dependencies IS
    'The dependency declarations a release was cut with, each pinned to the release it resolved to (releaseVersion + releaseFingerprint).';
