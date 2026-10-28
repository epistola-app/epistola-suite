-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- A release is a release however it arrived (ADR 0036, amendment of 2026-10-28): what a subscribed
-- catalog's source says is about the release the catalog is on, its highest, not an "installed" one.
-- The columns are named for what they hold; their values are kept.
ALTER TABLE catalog_upstream_checks RENAME COLUMN installed_availability TO upstream_availability;
ALTER TABLE catalog_upstream_checks RENAME COLUMN installed_availability_checked_at TO upstream_availability_checked_at;
ALTER TABLE catalog_upstream_checks RENAME COLUMN installed_archive_sha256 TO received_archive_sha256;
ALTER TABLE catalog_upstream_checks
    RENAME CONSTRAINT catalog_upstream_checks_installed_availability_check TO catalog_upstream_checks_upstream_availability_check;

COMMENT ON COLUMN catalog_upstream_checks.upstream_availability IS
    'Whether the source still offers the catalog''s highest release: AVAILABLE, BLOCKED or WITHDRAWN; NULL when the source says nothing of it (one cut here).';
COMMENT ON COLUMN catalog_upstream_checks.received_archive_sha256 IS
    'The digest of the archive last received from the source: its bytes, for integrity and a conditional refetch, not its content fingerprint.';
